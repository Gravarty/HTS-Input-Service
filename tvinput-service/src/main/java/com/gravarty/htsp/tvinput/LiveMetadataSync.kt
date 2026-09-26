package com.gravarty.htsp.tvinput

import android.content.Context
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspConnectionState
import com.gravarty.htsp.core.HtspRepository
import com.gravarty.htsp.provider.HtspLog
import com.gravarty.htsp.provider.HtspServerSync
import com.gravarty.htsp.provider.HtspSyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One live metadata sync per process, shared by the TV and the radio input service
 * (pvr.hts: one connection with enableAsyncMetadata). Runs while at least one service lives.
 */
object LiveMetadataSync {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var users = 0
    private var job: Job? = null
    private var metaConnection: HtspConnection? = null

    private val repository = HtspRepository()
    private val pendingLock = Any()
    private val changedChannels = HashSet<Long>()
    private val changedEvents = HashSet<Long>()
    private val deletedEvents = HashSet<Long>()
    private val changeSignal = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun acquire(context: Context) {
        if (users++ > 0) return
        val app = context.applicationContext
        // Own connection for metadata: the initial sync (all channels + EPG) would otherwise
        // queue in front of the subscribe reply and the stream packets on the same socket.
        val s = HtspServerSync.loadSettings(app)
        val meta = HtspConnection(s.host, s.port, s.username, s.password)
        metaConnection = meta
        job = serviceScope.launch { metadataLoop(app, meta) }
    }

    /** Settings changed: reconnect with the new values if the sync is running. */
    @Synchronized
    fun restart(context: Context) {
        if (users == 0) return
        job?.cancel()
        metaConnection?.disconnect()
        val app = context.applicationContext
        val s = HtspServerSync.loadSettings(app)
        val meta = HtspConnection(s.host, s.port, s.username, s.password)
        metaConnection = meta
        job = serviceScope.launch { metadataLoop(app, meta) }
    }

    @Synchronized
    fun release() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
        metaConnection?.disconnect()
        metaConnection = null
    }

    /**
     * Connect, request async metadata, full sync after initialSyncCompleted, then
     * write live changes. On disconnect: reconnect and start over, like pvr.hts
     * (tvheadend resends everything as "added" after a reconnect).
     */
    private suspend fun metadataLoop(context: Context, conn: HtspConnection) {
        while (currentCoroutineContext().isActive) {
            val settings = HtspServerSync.loadSettings(context)
            if (settings.host.isEmpty()) return // setup not done yet

            if (!conn.ensureConnected()) {
                delay(RECONNECT_DELAY_MS)
                continue
            }

            try { coroutineScope {
                repository.clear()
                synchronized(pendingLock) { changedChannels.clear(); changedEvents.clear(); deletedEvents.clear() }
                val syncManager = HtspSyncManager(context, conn, repository, settings)

                val subscribed = CompletableDeferred<Unit>()
                val collector = launch(Dispatchers.Default) {
                    conn.asyncMessages.onSubscription { subscribed.complete(Unit) }.collect { msg ->
                        if (msg.method !in METADATA_METHODS) return@collect
                        repository.handleAsyncMessage(msg)
                        if (repository.isInitialSyncCompleted.value && msg.method != "initialSyncCompleted") {
                            // tvheadend htsp_channel_update_nownext(): channelUpdate with only
                            // channelId/eventId/nextEventId on every programme change -> no channel row change
                            val nowNextOnly = msg.method == "channelUpdate" &&
                                msg.fields.keys.all { it in NOW_NEXT_FIELDS }
                            if (!nowNextOnly) {
                                recordChange(msg.method!!, msg.getLong("channelId"), msg.getLong("eventId"))
                            }
                        }
                    }
                }

                val writer = launch {
                    repository.isInitialSyncCompleted.first { it }
                    runCatching { syncManager.performSync() }
                        .onFailure { HtspLog.e("Live full sync failed: ${it.message}") }
                    while (isActive) {
                        changeSignal.receive()
                        delay(WRITE_DELAY_MS) // collect a burst of updates into one write
                        val (ch, ev, del) = synchronized(pendingLock) {
                            Triple(changedChannels.toSet(), changedEvents.toSet(), deletedEvents.toSet()).also {
                                changedChannels.clear(); changedEvents.clear(); deletedEvents.clear()
                            }
                        }
                        if (ch.isEmpty() && ev.isEmpty() && del.isEmpty()) continue
                        runCatching { syncManager.applyChanges(ch, ev, del) }
                            .onFailure { HtspLog.e("Live update failed: ${it.message}") }
                    }
                }

                subscribed.await()
                conn.enableAsyncMetadata(settings.enableEpg, settings.epgMaxTimeSeconds)

                // Stay here until the connection drops
                conn.stateFlow.first { it == HtspConnectionState.DISCONNECTED }
                collector.cancel()
                writer.cancel()
            } } catch (e: TimeoutCancellationException) {
                HtspLog.e("Metadata request timed out, reconnecting")
                conn.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HtspLog.e("Metadata connection failed: ${e.message}")
                conn.disconnect()
            }
            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun recordChange(method: String, channelId: Long?, eventId: Long?) {
        synchronized(pendingLock) {
            when (method) {
                "channelAdd", "channelUpdate", "channelDelete" -> channelId?.let { changedChannels.add(it) }
                "eventAdd", "eventUpdate" -> eventId?.let { deletedEvents.remove(it); changedEvents.add(it) }
                "eventDelete" -> eventId?.let { changedEvents.remove(it); deletedEvents.add(it) }
                else -> return
            }
        }
        changeSignal.trySend(Unit)
    }

    private const val RECONNECT_DELAY_MS = 5_000L
    private const val WRITE_DELAY_MS = 5_000L

    private val NOW_NEXT_FIELDS = setOf("method", "channelId", "eventId", "nextEventId")

    private val METADATA_METHODS = setOf(
        "channelAdd", "channelUpdate", "channelDelete",
        "tagAdd", "tagUpdate", "tagDelete",
        "eventAdd", "eventUpdate", "eventDelete",
        "dvrEntryAdd", "dvrEntryUpdate", "dvrEntryDelete",
        "initialSyncCompleted"
    )
}

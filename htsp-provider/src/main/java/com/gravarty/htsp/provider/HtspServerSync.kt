package com.gravarty.htsp.provider

import android.content.Context
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspRepository
import com.gravarty.htsp.core.HtspSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One full metadata sync: connect, enableAsyncMetadata (epg / epgMaxTime like
 * pvr.hts CTvheadend::Connected), wait for initialSyncCompleted, write TvContract.
 * Used by the setup and by the background worker.
 */
object HtspServerSync {

    enum class Result { OK, CONNECT_FAILED, TIMEOUT, NO_CHANNELS, WRITE_FAILED }

    /** Events for the channel scan page (same as the old htsptvinput EpgSyncTask.Listener). */
    interface Listener {
        fun onChannelFound(channelNumber: Int, channelName: String) {}
        fun onChannelsCompleted(channelCount: Int) {}
        fun onInitialSyncCompleted() {}
    }

    private val lock = Mutex() // setup and worker must not write at the same time

    fun loadSettings(context: Context): HtspSettings {
        val p = context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
        return HtspSettings(
            host = p.getString(HtspSettings.KEY_HOST, "") ?: "",
            port = p.getInt(HtspSettings.KEY_PORT, 9982),
            username = p.getString(HtspSettings.KEY_USERNAME, "") ?: "",
            password = p.getString(HtspSettings.KEY_PASSWORD, "") ?: "",
            profile = p.getString(HtspSettings.KEY_PROFILE, "") ?: "",
            enableEpg = p.getBoolean(HtspSettings.KEY_ENABLE_EPG, true),
            httpPort = p.getInt(HtspSettings.KEY_HTTP_PORT, HtspSettings.DEFAULT_HTTP_PORT),
            epgMaxTimeSeconds = p.getString(HtspSettings.KEY_EPG_MAX_TIME, null)?.toLongOrNull()
                ?: HtspSettings.DEFAULT_EPG_MAX_TIME
        )
    }

    suspend fun run(
        context: Context,
        settings: HtspSettings,
        onConnected: suspend () -> Unit = {},
        onProgress: suspend (String) -> Unit = {},
        listener: Listener? = null
    ): Result = lock.withLock {
        coroutineScope {
            val repository = HtspRepository()
            val connection = HtspConnection(settings.host, settings.port, settings.username, settings.password)

            val collectJob = launch(Dispatchers.Default) {
                var channelCount = 0
                var channelsDone = false
                connection.asyncMessages.collect { msg ->
                    repository.handleAsyncMessage(msg)
                    when (msg.method) {
                        "channelAdd" -> {
                            channelCount++
                            listener?.onChannelFound(
                                msg.getLong("channelNumber")?.toInt() ?: 0,
                                msg.getString("channelName") ?: ""
                            )
                        }
                        // tvheadend sends tags and channels first; anything else ends the channel list
                        "tagAdd", "tagUpdate", "channelUpdate" -> Unit
                        else -> if (!channelsDone && channelCount > 0) {
                            channelsDone = true
                            listener?.onChannelsCompleted(channelCount)
                        }
                    }
                    if (msg.method == "initialSyncCompleted") listener?.onInitialSyncCompleted()
                }
            }

            try {
                if (!connection.connect()) return@coroutineScope Result.CONNECT_FAILED
                onConnected()

                onProgress(
                    if (settings.enableEpg) "Fetching channels and EPG from Tvheadend..."
                    else "Fetching channels from Tvheadend..."
                )
                connection.enableAsyncMetadata(settings.enableEpg, settings.epgMaxTimeSeconds)

                withTimeoutOrNull(120_000) { repository.isInitialSyncCompleted.first { it } }
                    ?: return@coroutineScope Result.TIMEOUT
                if (repository.channels.value.isEmpty()) return@coroutineScope Result.NO_CHANNELS

                try {
                    HtspSyncManager(context, connection, repository, settings).performSync(onProgress)
                } catch (e: Exception) {
                    HtspLog.e("Sync failed", e)
                    return@coroutineScope Result.WRITE_FAILED
                }
                Result.OK
            } finally {
                collectJob.cancel()
                connection.disconnect()
            }
        }
    }
}

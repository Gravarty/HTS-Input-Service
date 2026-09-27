package com.gravarty.htsp.core

import com.gravarty.htsp.core.model.HtspMuxPacket
import com.gravarty.htsp.core.model.HtspStream
import com.gravarty.htsp.core.model.SignalStatus
import com.gravarty.htsp.core.model.TimeshiftStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Direct port of Kodi pvr.hts 'Subscription.cpp'
 */
class HtspSubscription(
    private val connection: HtspConnection,
    val subscriptionId: Long = nextSubscriptionId.getAndIncrement()
) {
    private val _signalStatus = MutableStateFlow(SignalStatus())
    val signalStatus: StateFlow<SignalStatus> = _signalStatus.asStateFlow()

    /** null until the server sends timeshiftStatus (i.e. timeshift is enabled in tvheadend). */
    private val _timeshiftStatus = MutableStateFlow<TimeshiftStatus?>(null)
    val timeshiftStatus: StateFlow<TimeshiftStatus?> = _timeshiftStatus.asStateFlow()

    private val _streams = MutableStateFlow<List<HtspStream>>(emptyList())
    val streams: StateFlow<List<HtspStream>> = _streams.asStateFlow()

    /**
     * Number of subscriptionStart messages. A count, not the stream list: after a restart
     * the list can be identical, but tvheadend's tsfix restarts the timestamps at 0.
     */
    private val _startCount = MutableStateFlow(0)
    val startCount: StateFlow<Int> = _startCount.asStateFlow()

    private val _packets = MutableSharedFlow<HtspMuxPacket>(replay = 1, extraBufferCapacity = 512)
    val packets: SharedFlow<HtspMuxPacket> = _packets.asSharedFlow()

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private val _errorFlow = MutableSharedFlow<Throwable>(extraBufferCapacity = 16)
    val errorFlow: SharedFlow<Throwable> = _errorFlow.asSharedFlow()

    /** Same fields as pvr.hts Subscription::SendSubscribe. normts=1 -> timestamps start at 0. */
    suspend fun subscribe(channelId: Long, weight: Int = SUBSCRIPTION_WEIGHT_SERVERCONF, profile: String = ""): Boolean {
        val params = mutableMapOf<String, Any>(
            "channelId" to channelId,
            "subscriptionId" to subscriptionId,
            "weight" to weight.toLong(),
            "timeshiftPeriod" to 0xFFFFFFFFL,
            "normts" to 1L,
            "queueDepth" to PACKET_QUEUE_DEPTH
        )
        if (profile.isNotEmpty()) params["profile"] = profile
        subscribeSent = true
        val response = connection.sendRequest("subscribe", params)
        val error = response.getString("error")
        val success = error == null
        _isSubscribed.value = success
        if (error != null) {
            _errorFlow.emit(RuntimeException("HTSP Subscription Error: $error"))
        }
        return success
    }

    /** Set once "subscribe" went out; the server may run the subscription even if we never saw the reply. */
    @Volatile
    private var subscribeSent = false

    suspend fun unsubscribe(): Boolean {
        if (!subscribeSent) return true
        subscribeSent = false
        val params = mapOf("subscriptionId" to subscriptionId)
        val response = connection.sendRequest("unsubscribe", params)
        _isSubscribed.value = false
        return response.getString("error") == null
    }

    /** Set by subscriptionSkip while a seek is pending (pvr.hts m_seektime). */
    @Volatile
    private var pendingSkip: CompletableDeferred<Long?>? = null

    /**
     * pvr.hts HTSPDemuxer::Seek + Subscription::SendSeek: absolute seek in stream time (µs),
     * then wait for subscriptionSkip. Returns the position the server skipped to, or null.
     */
    suspend fun seek(timeUs: Long, timeoutMs: Long = 15_000): Long? {
        if (!_isSubscribed.value) return null
        val skip = CompletableDeferred<Long?>()
        pendingSkip = skip
        try {
            val response = connection.sendRequest(
                "subscriptionSeek",
                mapOf("subscriptionId" to subscriptionId, "time" to timeUs, "absolute" to 1L)
            )
            if (response.getString("error") != null) return null
            return withTimeoutOrNull(timeoutMs) { skip.await() }
        } finally {
            pendingSkip = null
        }
    }

    /** Speed in tvheadend units (pvr.hts sends Kodi speed / 10): 0 = pause, 100 = normal. */
    @Volatile
    var actualSpeed: Int = SPEED_NORMAL
        private set

    /** pvr.hts Subscription::SendSpeed */
    suspend fun setSpeed(speed: Int): Boolean {
        if (!_isSubscribed.value) return false
        val response = connection.sendRequest(
            "subscriptionSpeed",
            mapOf("subscriptionId" to subscriptionId, "speed" to speed.toLong())
        )
        return response.getString("error") == null
    }

    suspend fun handleMessage(msg: HtsMessage) {
        val subId = msg.getLong("subscriptionId")
        if (subId != subscriptionId) return

        when (msg.method) {
            "muxpkt" -> {
                HtspMuxPacket.fromHtsMessage(msg)?.let { packet ->
                    _packets.emit(packet)
                }
            }
            "subscriptionStart" -> {
                _isSubscribed.value = true
                @Suppress("UNCHECKED_CAST")
                val streamList = msg.getList("streams") as? List<Map<String, Any>>
                if (streamList != null) {
                    _streams.value = streamList.map { HtspStream.fromMap(it) }
                    // pvr.hts: every subscriptionStart = DEMUX_SPECIALID_STREAMCHANGE
                    _startCount.value = _startCount.value + 1
                }
            }
            "subscriptionStop" -> {
                // pvr.hts ParseSubscriptionStop() does nothing: tvheadend sends it before a
                // stream restart (SMT_STOP), followed by a new subscriptionStart.
                println("[HTSP] subscriptionStop: status=${msg.getString("status")} " +
                    "subscriptionError=${msg.getString("subscriptionError")}")
            }
            "signalStatus" -> {
                _signalStatus.value = SignalStatus.fromHtsMessage(msg)
            }
            "timeshiftStatus" -> {
                (_timeshiftStatus.value ?: TimeshiftStatus()).update(msg)?.let { _timeshiftStatus.value = it }
            }
            "subscriptionSkip" -> {
                // pvr.hts ParseSubscriptionSkip: no "time" -> invalid seek; negative -> 0
                val skip = pendingSkip ?: return
                skip.complete(msg.getLong("time")?.coerceAtLeast(0))
            }
            "subscriptionSpeed" -> {
                msg.getLong("speed")?.let { actualSpeed = it.toInt() }
            }
        }
    }

    companion object {
        private val nextSubscriptionId = AtomicLong(1)

        /** pvr.hts Subscription.h, used by OpenLiveStream */
        const val SUBSCRIPTION_WEIGHT_SERVERCONF = 0

        /** tvheadend speed units (pvr.hts SPEED_NORMAL 1000 / 10) */
        const val SPEED_NORMAL = 100
        const val SPEED_PAUSED = 0

        /** pvr.hts Subscription.h */
        const val PACKET_QUEUE_DEPTH = 10_000_000L
    }
}

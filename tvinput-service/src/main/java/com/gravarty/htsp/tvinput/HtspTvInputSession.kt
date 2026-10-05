package com.gravarty.htsp.tvinput

import com.gravarty.htsp.provider.HtspLog
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.media.PlaybackParams
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.gravarty.htsp.provider.HtspRecordingMapper
import com.gravarty.htsp.tvinput.player.HtspVfsDataSource
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.gravarty.htsp.tvinput.player.PcmOnlyAudioSink
import com.gravarty.htsp.tvinput.player.StereoDownmix
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspSettings
import com.gravarty.htsp.core.HtspSubscription
import com.gravarty.htsp.tvinput.mapper.TvTrackInfoMapper
import com.gravarty.htsp.tvinput.player.HtspMediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class HtspTvInputSession(
    private val context: Context,
    /** Asked on every tune, so changed connection settings apply without a new session. */
    private val connectionProvider: () -> HtspConnection
) : TvInputService.Session(context) {

    private var connection: HtspConnection = connectionProvider()

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var exoPlayer: ExoPlayer? = null
    private var subtitleView: SubtitleView? = null
    private var captionsEnabled = false
    private var subscription: HtspSubscription? = null
    private var routingJob: Job? = null
    private var tuneJob: Job? = null
    private var surface: Surface? = null
    private var mediaSource: HtspMediaSource? = null
    private var seekJob: Job? = null
    private var trickJob: Job? = null

    /** Playing a recording (onTimeShiftPlay) instead of live TV */
    private var recordingPlayback = false

    /** pvr.hts m_startTime: wall clock of the first packet; stream time 0 (normts) maps to it. */
    @Volatile private var startTimeMs = 0L
    @Volatile private var awaitingFirstPacket = true
    private var restartJob: Job? = null
    private var statsJob: Job? = null
    private var timeshiftAnnounced = false

    private val subscriptionMethods = setOf(
        "muxpkt", "subscriptionStart", "subscriptionStop",
        "subscriptionStatus", "subscriptionSkip", "subscriptionSpeed", "subscriptionGrace",
        "signalStatus", "timeshiftStatus", "queueStatus"
    )

    init {
        // Subtitles are drawn by this input in its own overlay (TIF onCreateOverlayView)
        setOverlayViewEnabled(true)
    }

    override fun onCreateOverlayView(): View =
        SubtitleView(context).also { subtitleView = it }

    override fun onRelease() {
        releasePlayerAndSubscription()
        sessionScope.cancel()
        ioScope.cancel()
    }

    private var subscriptionErrorShown = false

    /** Setting "signal values" (needs a TV app that shows them) */
    private val signalStatusEnabled: Boolean
        get() = context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(HtspSettings.KEY_SIGNAL_STATUS, false)

    /** Setting "detailed error messages" (needs a TV app that shows these reasons) */
    private val detailedErrors: Boolean
        get() = context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(HtspSettings.KEY_DETAILED_ERRORS, false)

    /**
     * Like pvr.hts Subscription::ParseSubscriptionStatus: "subscriptionError" is absent when
     * everything is fine. pvr.hts shows a Kodi notification; with TIF the TV app shows the
     * matching "video unavailable" reason.
     */
    private fun onSubscriptionStatus(error: String?) {
        if (error == null) {
            if (subscriptionErrorShown) {
                subscriptionErrorShown = false
                // picture already running again: show it; otherwise the first frame does
                if (exoPlayer?.playbackState == Player.STATE_READY) notifyVideoAvailable()
            }
            return
        }
        val reason = when (error) {
            "badSignal" -> TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
            "noFreeAdapter", "userLimit" ->
                if (Build.VERSION.SDK_INT >= 30) TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE
                else TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
            "scrambled" ->
                if (Build.VERSION.SDK_INT >= 30) TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN
                else TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
            else -> TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN // tuningFailed, userAccess, unknown
        }
        HtspLog.e("Subscription error: $error -> video unavailable reason $reason")
        subscriptionErrorShown = true
        notifyVideoUnavailable(reason)
    }

    private var zapStartMs = 0L // TEMPORARY zap time

    override fun onSetSurface(surface: Surface?): Boolean {
        HtspLog.i("onSetSurface ${surface?.let { "valid=${it.isValid} ${System.identityHashCode(it)}" }} " +
            "(player ${if (exoPlayer != null) "running" else "none"})")
        this.surface = surface
        exoPlayer?.let { player ->
            player.setVideoSurface(surface)
            // Player stopped by a decoder error on the destroyed surface: resume on the new one
            if (surface != null && surface.isValid && player.playerError != null) {
                HtspLog.i("Resuming after surface loss")
                player.prepare()
            }
        }
        return true
    }

    override fun onSetStreamVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    override fun onTune(channelUri: Uri?): Boolean {
        if (channelUri == null) return false

        zapStartMs = android.os.SystemClock.elapsedRealtime() // TEMPORARY zap time
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        releasePlayerAndSubscription()

        val htspChannelId = getHtspChannelIdFromUri(channelUri)
        HtspLog.i("onTune $channelUri -> HTSP channel $htspChannelId")
        if (htspChannelId == null) return false
        val profile = context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
            .getString(HtspSettings.KEY_PROFILE, "") ?: ""

        connection = connectionProvider()
        ioScope.launch { RecentChannelsPreview.onChannelTuned(context, channelUri) }
        tuneJob = sessionScope.launch {
            val connected = withContext(Dispatchers.IO) { connection.ensureConnected() }
            if (!connected) {
                HtspLog.e("Could not connect to Tvheadend server!")
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return@launch
            }

            HtspLog.i("Connected, subscribing (profile='$profile')")
            val direct = context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(HtspSettings.KEY_DIRECT_PACKETS, true)
            val sub = HtspSubscription(connection, directPackets = direct)
            subscription = sub

            // Route this subscription's messages before subscribing, otherwise
            // subscriptionStart / muxpkt arrive with nobody listening.
            val routingReady = CompletableDeferred<Unit>()
            awaitingFirstPacket = true
            sub.packetListener = { packet ->
                if (awaitingFirstPacket) {
                    awaitingFirstPacket = false
                    startTimeMs = System.currentTimeMillis()
                    HtspLog.i("<- first muxpkt (stream ${packet.streamIndex})")
                }
            }
            routingJob = ioScope.launch {
                connection.asyncMessages
                    .onSubscription { routingReady.complete(Unit) }
                    .collect { msg ->
                        if (msg.method !in subscriptionMethods) return@collect
                        if (msg.getLong("subscriptionId") == sub.subscriptionId) {
                            if (msg.method != "muxpkt") {
                                val fields = msg.fields.mapValues { (_, v) ->
                                    when (v) {
                                        is com.gravarty.htsp.core.HtsBin -> "<${v.length} bytes>"
                                        is List<*> -> "<list ${v.size}>"
                                        else -> v
                                    }
                                }
                                HtspLog.i("<- ${msg.method} $fields")
                            }
                            // Signal values for the TV app's signal display (SignalStatusProvider)
                            if (msg.method == "signalStatus" && signalStatusEnabled) SignalStatusStore.update(msg)
                            if (detailedErrors && msg.method == "subscriptionStatus") {
                                onSubscriptionStatus(msg.getString("subscriptionError"))
                            }
                        }
                        sub.handleMessage(msg)
                    }
            }
            routingReady.await()

            observeStreams(sub)
            observeTimeshift(sub)

            val subscribed = try {
                withContext(Dispatchers.IO) { sub.subscribe(htspChannelId, profile = profile) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                HtspLog.e("subscribe: no reply from server")
                false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // retuned while waiting
            } catch (e: Exception) {
                HtspLog.e("subscribe failed", e)
                false
            }
            HtspLog.i("subscribe -> $subscribed (subscriptionId ${sub.subscriptionId})")
            if (!subscribed) {
                HtspLog.e("Failed to subscribe to channel $htspChannelId")
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return@launch
            }

            val player = newPlayer()
            val source = buildMediaSource(sub)
            mediaSource = source
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            observeStreamRestart(sub, player)
        }

        return true
    }

    private val forceStereo: Boolean
        get() = context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(HtspSettings.KEY_FORCE_STEREO, true)

    /** Player for live TV and recordings: PCM audio, captions per TIF setting, notify* wiring. */
    private fun newPlayer(): ExoPlayer {
        // Audio is decoded to PCM (Kodi default: passthrough off). This TV reports AC3
        // passthrough support, but AudioTrack rejects it (getAudioTrackMinBufferSize).
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = PcmOnlyAudioSink(
                DefaultAudioSink.Builder(context)
                    // "force stereo": downmix in the plugin (hardware decoders may ignore the
                    // channel-count hint below and output 6 channels)
                    .setAudioProcessors(if (forceStereo) arrayOf(StereoDownmix.processor()) else emptyArray())
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            )

            // Surface changes (TV app closed/reopened) re-create the video codec instead of
            // MediaCodec.setOutputSurface(): some MTK decoders keep rendering to the old, invisible
            // surface. ExoPlayer does the same for its list of known devices
            // (codecNeedsSetOutputSurfaceWorkaround); costs a moment until the next keyframe.
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                out.add(object : MediaCodecVideoRenderer(
                    context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                    eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAMES_TO_NOTIFY
                ) {
                    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean = true
                })
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                // AC3 / E-AC3 always via ffmpeg, like Kodi: hardware Dolby decoders differ per
                // device in applying dialnorm (measured: Vestel -8 dB, PEAQ none), ffmpeg never
                // applies it, so AC3 plays at the same level as MP2 on every device.
                // All other formats stay on the hardware decoders.
                val selector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                    if (mimeType == MimeTypes.AUDIO_AC3 || mimeType == MimeTypes.AUDIO_E_AC3) emptyList()
                    else mediaCodecSelector.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                }
                out.add(object : MediaCodecAudioRenderer(
                    context, selector, enableDecoderFallback, eventHandler, eventListener, audioSink
                ) {
                    // "force stereo": ask the decoder for at most 2 channels (Android 11+)
                    override fun getMediaFormat(
                        format: Format, codecMimeType: String, codecMaxInputSize: Int, codecOperatingRate: Float
                    ): MediaFormat =
                        super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate).apply {
                            if (forceStereo && Build.VERSION.SDK_INT >= 30) {
                                setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 2)
                            }
                        }
                })
                // ffmpeg: AC3 / E-AC3, and any other format the device has no decoder for
                out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
            }
        }
        val player = ExoPlayer.Builder(context, renderers).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsEnabled)
            .build()
        exoPlayer = player
        // Multimedia tunneling (source.android.com/docs/devices/tv/multimedia-tunneling):
        // ExoPlayer only uses it if the decoder supports it, otherwise plays as before.
        val tunneling = context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(HtspSettings.KEY_TUNNELING, false) // off: breaks timeshift rewind on some TVs
        (player.trackSelector as? DefaultTrackSelector)?.let {
            it.setParameters(
                it.buildUponParameters()
                    .setTunnelingEnabled(tunneling)
                    // "force stereo": prefer a stereo track (e.g. the MP2 track next to AC3 5.1);
                    // only without one the multichannel track is used and downmixed
                    .setMaxAudioChannelCount(if (forceStereo) 2 else Int.MAX_VALUE)
            )
        }
        var firstFrameShown = false
        var buffering = false
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracks.groups.forEach { g ->
                    HtspLog.i("Track ${g.mediaTrackGroup.getFormat(0).sampleMimeType} supported=${g.isSupported} selected=${g.isSelected}")
                }
                // Report the audio track the player actually plays (TrackGroup id = TvTrackInfo id),
                // not just the first one in the list
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let {
                    notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, it.mediaTrackGroup.id)
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleView?.setCues(cueGroup.cues)
            }

            override fun onRenderedFirstFrame() {
                HtspLog.i("First frame rendered")
                firstFrameShown = true
                // TEMPORARY: zap time, error log = visible in release
                if (zapStartMs > 0) {
                    HtspLog.e("ZAP ${android.os.SystemClock.elapsedRealtime() - zapStartMs} ms")
                    zapStartMs = 0
                }
                notifyVideoAvailable()
            }

            override fun onPlaybackStateChanged(state: Int) {
                HtspLog.i("Player state $state (1 idle, 2 buffering, 3 ready, 4 ended), buffered ${player.bufferedPosition} ms")
                if (exoPlayer !== player) return
                when (state) {
                    // Re-buffering after playback started: let the TV app show its loading state
                    // (TIF: VIDEO_UNAVAILABLE_REASON_BUFFERING). Before the first frame the
                    // TUNING state from onTune is still shown.
                    // (a server error such as "no signal" stays shown instead of the loading state)
                    Player.STATE_BUFFERING -> if (firstFrameShown && !buffering && !subscriptionErrorShown) {
                        buffering = true
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
                    }
                    Player.STATE_READY -> {
                        if (!player.currentTracks.containsType(C.TRACK_TYPE_VIDEO)) {
                            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY)
                        } else if (buffering && !subscriptionErrorShown) {
                            notifyVideoAvailable()
                        }
                        buffering = false
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (exoPlayer !== player) {
                    // e.g. "Player release timed out" while switching channels: the old player
                    // is already gone, the new channel must not show "video unavailable"
                    HtspLog.e("Ignored error of released player: ${error.errorCodeName}")
                    return
                }
                val currentSurface = surface
                if (currentSurface == null || !currentSurface.isValid) {
                    // The system destroyed the surface before onSetSurface(null) arrived (TV app
                    // closed): the decoder failed on the dead surface, not on the stream.
                    // No "video unavailable"; onSetSurface() with a new surface prepares again.
                    HtspLog.e("Decoder error without valid surface (ignored): ${error.errorCodeName}")
                    return
                }
                HtspLog.e("Player error: ${error.errorCodeName}", error)
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
            }
        })
        player.setVideoSurface(surface)
        startPlaybackStats(player)
        return player
    }

    /**
     * Debug: every 5 s the video decoder counters (frames rendered / dropped / skipped in the
     * interval), the stream frame rate and the current display refresh rate.
     */
    private fun startPlaybackStats(player: ExoPlayer) {
        statsJob?.cancel()
        statsJob = sessionScope.launch {
            var lastRendered = 0
            var lastDropped = 0
            var lastSkipped = 0
            while (currentCoroutineContext().isActive) {
                delay(5000)
                if (exoPlayer !== player) return@launch
                val c = player.videoDecoderCounters
                if (c == null) {
                    HtspLog.i("Stats: no video decoder counters (state ${player.playbackState})")
                    continue
                }
                c.ensureUpdated()
                val rendered = c.renderedOutputBufferCount - lastRendered
                val dropped = c.droppedBufferCount - lastDropped
                val skipped = c.skippedOutputBufferCount - lastSkipped
                lastRendered = c.renderedOutputBufferCount
                lastDropped = c.droppedBufferCount
                lastSkipped = c.skippedOutputBufferCount
                val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                HtspLog.i(
                    "Stats 5s: rendered $rendered (${rendered / 5} fps), dropped $dropped, skipped $skipped, " +
                        "stream ${player.videoFormat?.frameRate} fps ${player.videoFormat?.width}x${player.videoFormat?.height}, " +
                        "decoder ${player.videoFormat?.sampleMimeType}, display ${display?.refreshRate} Hz, " +
                        "tunneling ${player.isTunnelingEnabled}"
                )
            }
        }
    }

    // ---- Timeshift, like pvr.hts HTSPDemuxer (Speed / Seek / GetStreamTimes) ----

    private fun observeTimeshift(sub: HtspSubscription) {
        sub.timeshiftStatus
            .onEach { status ->
                // tvheadend only sends timeshiftStatus when timeshift is enabled on the server
                if (status != null && !timeshiftAnnounced) {
                    timeshiftAnnounced = true
                    HtspLog.i("Timeshift available")
                    notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
                }
            }
            .launchIn(sessionScope)
    }

    override fun onTimeShiftPause() {
        stopTrickPlay()
        if (recordingPlayback) { exoPlayer?.playWhenReady = false; return }
        val sub = subscription ?: return
        exoPlayer?.playWhenReady = false
        ioScope.launch { runCatching { sub.setSpeed(HtspSubscription.SPEED_PAUSED) } }
    }

    override fun onTimeShiftResume() {
        stopTrickPlay()
        if (recordingPlayback) { exoPlayer?.playWhenReady = true; return }
        val sub = subscription ?: return
        ioScope.launch { runCatching { sub.setSpeed(HtspSubscription.SPEED_NORMAL) } }
        exoPlayer?.playWhenReady = true
    }

    override fun onTimeShiftSeekTo(timeMs: Long) {
        stopTrickPlay()
        if (recordingPlayback) { exoPlayer?.seekTo(timeMs.coerceAtLeast(0)); return }
        seekJob?.cancel()
        seekJob = sessionScope.launch { seekTo(timeMs) }
    }

    /**
     * Fast forward / rewind. pvr.hts maps every speed != 0 to normal (HTSPDemuxer::Speed);
     * Kodi's player does trick play by seeking repeatedly. Same here: the server keeps
     * normal speed, the position is moved by speed × elapsed time with subscriptionSeek.
     */
    override fun onTimeShiftSetPlaybackParams(params: PlaybackParams) {
        val speed = params.speed
        stopTrickPlay()
        if (recordingPlayback) {
            when (speed) {
                1f -> exoPlayer?.playWhenReady = true
                0f -> Unit
                else -> trickJob = sessionScope.launch { trickPlayRecording(speed) }
            }
            return
        }
        val sub = subscription ?: return
        if (speed == 1f) {
            ioScope.launch { runCatching { sub.setSpeed(HtspSubscription.SPEED_NORMAL) } }
            exoPlayer?.playWhenReady = true
            return
        }
        if (speed == 0f) return
        trickJob = sessionScope.launch { trickPlay(speed) }
    }

    private suspend fun trickPlay(speed: Float) {
        val sub = subscription ?: return
        val player = exoPlayer ?: return
        HtspLog.i("Trick play x$speed")
        runCatching { withContext(Dispatchers.IO) { sub.setSpeed(HtspSubscription.SPEED_NORMAL) } }
        player.playWhenReady = false

        val anchorClock = SystemClock.elapsedRealtime()
        val anchorPos = onTimeShiftGetCurrentPosition()
        if (anchorPos == TvInputManager.TIME_SHIFT_INVALID_TIME) return

        while (currentCoroutineContext().isActive) {
            delay(TRICK_STEP_MS)
            val status = sub.timeshiftStatus.value ?: return
            val bufferStart = startTimeMs + status.start / 1000
            val bufferEnd = startTimeMs + status.end / 1000
            val target = anchorPos + (speed * (SystemClock.elapsedRealtime() - anchorClock)).toLong()

            when {
                target <= bufferStart -> {       // reached the oldest buffered position
                    seekTo(bufferStart)
                    player.playWhenReady = true
                    return
                }
                target >= bufferEnd -> {         // caught up with live
                    seekTo(bufferEnd)
                    player.playWhenReady = true
                    return
                }
                else -> seekTo(target)
            }
        }
    }

    /** Same stepping as live trick play (Kodi seeks repeatedly), on the recording's timeline. */
    private suspend fun trickPlayRecording(speed: Float) {
        val player = exoPlayer ?: return
        HtspLog.i("Recording trick play x$speed")
        player.playWhenReady = false
        val anchorClock = SystemClock.elapsedRealtime()
        val anchorPos = player.currentPosition
        while (currentCoroutineContext().isActive) {
            delay(TRICK_STEP_MS)
            val target = anchorPos + (speed * (SystemClock.elapsedRealtime() - anchorClock)).toLong()
            val duration = player.duration
            when {
                target <= 0 -> { player.seekTo(0); player.playWhenReady = true; return }
                duration != C.TIME_UNSET && target >= duration -> {
                    player.seekTo(duration); player.playWhenReady = true; return
                }
                else -> player.seekTo(target)
            }
        }
    }

    // ---- Recordings (pvr.hts HTSPVFS) ----

    /**
     * Plays a recording from TvContract.RecordedPrograms (TIF onTimeShiftPlay). The file is
     * read over HTSP like pvr.hts HTSPVFS; a running recording has no fixed length.
     */
    override fun onTimeShiftPlay(recordedProgramUri: Uri) {
        releasePlayerAndSubscription()
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)

        var dataUri: Uri? = null
        var endMs = 0L
        try {
            context.contentResolver.query(
                recordedProgramUri,
                arrayOf(
                    TvContract.RecordedPrograms.COLUMN_RECORDING_DATA_URI,
                    TvContract.RecordedPrograms.COLUMN_END_TIME_UTC_MILLIS
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    dataUri = c.getString(0)?.let { Uri.parse(it) }
                    endMs = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            HtspLog.e("Recorded program query failed", e)
        }
        val uri = dataUri
        if (HtspRecordingMapper.recordingIdFromDataUri(uri) == null) {
            HtspLog.e("Not an HTSP recording: $recordedProgramUri")
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
            return
        }
        val growing = endMs > System.currentTimeMillis()

        recordingPlayback = true
        connection = connectionProvider()
        val conn = connection
        tuneJob = sessionScope.launch {
            val connected = withContext(Dispatchers.IO) { conn.ensureConnected() }
            if (!connected) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return@launch
            }
            HtspLog.i("Playing recording $uri (running: $growing)")
            val player = newPlayer()
            val source = ProgressiveMediaSource.Factory(
                HtspVfsDataSource.Factory(conn, growing), DefaultExtractorsFactory()
            ).createMediaSource(MediaItem.fromUri(uri!!))
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
        }
    }

    private fun stopTrickPlay() {
        trickJob?.cancel()
        trickJob = null
    }

    /** Wall-clock position -> subscriptionSeek -> wait for subscriptionSkip -> player seek. */
    private suspend fun seekTo(timeMs: Long) {
        val sub = subscription ?: return
        val status = sub.timeshiftStatus.value ?: return
        val period = mediaSource?.currentPeriod ?: return
        if (startTimeMs == 0L) return

        val targetUs = ((timeMs - startTimeMs) * 1000).coerceIn(status.start, status.end)
        period.beginSeek()
        val skippedUs = try {
            withContext(Dispatchers.IO) { sub.seek(targetUs) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            period.cancelSeek(); throw e
        } catch (e: Exception) {
            HtspLog.e("subscriptionSeek failed", e); null
        }
        if (skippedUs == null) {
            HtspLog.e("Seek to $targetUs µs: no valid subscriptionSkip")
            period.cancelSeek()
            return
        }
        HtspLog.i("Seek to $targetUs µs -> server skipped to $skippedUs µs")
        exoPlayer?.seekTo(skippedUs / 1000)
    }

    override fun onTimeShiftGetStartPosition(): Long {
        if (recordingPlayback) return 0 // TIF: recorded programs start at 0
        val status = subscription?.timeshiftStatus?.value
        if (status == null || startTimeMs == 0L) return TvInputManager.TIME_SHIFT_INVALID_TIME
        return startTimeMs + status.start / 1000
    }

    override fun onTimeShiftGetCurrentPosition(): Long {
        if (recordingPlayback) return exoPlayer?.currentPosition ?: TvInputManager.TIME_SHIFT_INVALID_TIME
        val player = exoPlayer
        if (player == null || startTimeMs == 0L) return TvInputManager.TIME_SHIFT_INVALID_TIME
        return startTimeMs + player.currentPosition
    }

    override fun onSelectTrack(type: Int, trackId: String?): Boolean {
        val player = exoPlayer ?: return false
        val trackType = when (type) {
            TvTrackInfo.TYPE_AUDIO -> C.TRACK_TYPE_AUDIO
            TvTrackInfo.TYPE_VIDEO -> C.TRACK_TYPE_VIDEO
            TvTrackInfo.TYPE_SUBTITLE -> C.TRACK_TYPE_TEXT
            else -> return false
        }

        if (trackId == null) {
            if (trackType != C.TRACK_TYPE_TEXT) return false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            subtitleView?.setCues(emptyList())
            notifyTrackSelected(type, null)
            return true
        }

        // TvTrackInfo id == TrackGroup id == HTSP stream index
        val group = player.currentTracks.groups
            .firstOrNull { it.type == trackType && it.mediaTrackGroup.id == trackId } ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        notifyTrackSelected(type, trackId)
        return true
    }

    override fun onSetCaptionEnabled(enabled: Boolean) {
        captionsEnabled = enabled
        if (!enabled) subtitleView?.setCues(emptyList())
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    private fun getHtspChannelIdFromUri(channelUri: Uri): Long? {
        try {
            context.contentResolver.query(
                channelUri,
                arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.toLongOrNull()?.let { if (it != 0L) return it }
                }
            }
        } catch (e: Exception) {
            HtspLog.e("Failed to resolve channel ID from URI: ${e.message}")
        }
        return null
    }

    private fun publishTracks(tracks: List<TvTrackInfo>) {
        notifyTracksChanged(tracks)
    }

    private fun buildMediaSource(sub: HtspSubscription): HtspMediaSource =
        HtspMediaSource(sub, ioScope) { index, num, den ->
            sessionScope.launch {
                val streams = sub.streams.value.map {
                    if (it.index == index) it.copy(aspectNum = num, aspectDen = den) else it
                }
                publishTracks(TvTrackInfoMapper.toTvTrackInfoList(streams))
                // re-select so Live Channels re-reads size / pixel aspect of the video track
                notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, index.toString())
            }
        }

    /**
     * Stream restart on the server (e.g. regional window): subscriptionStop, then a new
     * subscriptionStart with possibly new streams, and timestamps starting at 0 again
     * (tvheadend tsfix_start). pvr.hts answers with DEMUX_SPECIALID_STREAMCHANGE and Kodi
     * reopens the decoders; here the player gets a new media source on the same
     * subscription (no new subscribe). The first start is handled by the initial prepare.
     */
    private fun observeStreamRestart(sub: HtspSubscription, player: ExoPlayer) {
        var handled = 1
        restartJob?.cancel()
        restartJob = sub.startCount
            .onEach { count ->
                if (count <= handled || exoPlayer !== player) return@onEach
                handled = count
                HtspLog.i("Stream restart (subscriptionStart #$count), re-preparing player")
                stopTrickPlay()
                seekJob?.cancel()
                awaitingFirstPacket = true
                val source = buildMediaSource(sub)
                mediaSource = source
                player.setMediaSource(source)
                player.prepare()
            }
            .launchIn(sessionScope)
    }

    private fun observeStreams(sub: HtspSubscription) {
        sub.streams
            .onEach { streams ->
                if (streams.isNotEmpty()) HtspLog.i("subscriptionStart: " + streams.joinToString { "${it.index}:${it.type} ${it.width}x${it.height} ${it.aspectNum}:${it.aspectDen}" })
                val tvTracks = TvTrackInfoMapper.toTvTrackInfoList(streams)
                publishTracks(tvTracks)
                tvTracks.firstOrNull { it.type == TvTrackInfo.TYPE_VIDEO }?.let {
                    notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, it.id)
                }
            }
            .launchIn(sessionScope)
    }

    private fun releasePlayerAndSubscription() {
        statsJob?.cancel()
        statsJob = null
        recordingPlayback = false
        restartJob?.cancel()
        restartJob = null
        subtitleView?.setCues(emptyList())
        stopTrickPlay()
        seekJob?.cancel()
        seekJob = null
        mediaSource = null
        startTimeMs = 0L
        timeshiftAnnounced = false
        tuneJob?.cancel()
        tuneJob = null
        // Clear the field first: ExoPlayer reports a release timeout as player error from inside
        // release(); the listener below ignores errors of players that are no longer current.
        val oldPlayer = exoPlayer
        exoPlayer = null
        oldPlayer?.release()
        routingJob?.cancel()
        routingJob = null

        val sub = subscription ?: return
        SignalStatusStore.clear()
        subscription = null
        sub.release()
        // Own scope: must still run when the session scopes are cancelled in onRelease().
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { sub.unsubscribe() }
        }
    }

    private companion object {
        /** Interval between trick-play seeks (own choice, pvr.hts leaves this to Kodi's player). */
        const val TRICK_STEP_MS = 500L
    }
}

/** Same value as DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY */
private const val MAX_DROPPED_VIDEO_FRAMES_TO_NOTIFY = 50

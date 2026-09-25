package com.gravarty.htsp.tvinput.player

import com.gravarty.htsp.provider.HtspLog

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.dvb.DvbParser
import com.gravarty.htsp.core.HtspSubscription
import com.gravarty.htsp.core.model.HtspMuxPacket
import com.gravarty.htsp.core.model.HtspStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Push-based MediaPeriod, following pvr.hts HTSPDemuxer: muxpkt payloads go unchanged
 * into per-stream queues, pts as sent by the server (subscribed with normts=1, so the
 * stream starts near 0). Android-only additions: video starts at a keyframe (MediaCodec
 * cannot start mid-GOP like ffmpeg) and AAC ADTS headers are converted to csd-0.
 */
@OptIn(UnstableApi::class)
class HtspMediaPeriod(
    private val subscription: HtspSubscription,
    private val allocator: Allocator,
    private val scope: CoroutineScope,
    /** MPEG-2 aspect change seen in the stream: stream index, aspect num, aspect den. */
    private val onAspectChanged: (Int, Int, Int) -> Unit = { _, _, _ -> }
) : MediaPeriod {

    private class Track(
        val index: Int,
        val format: Format,
        val queue: SampleQueue,
        val isVideo: Boolean,
        val isAac: Boolean,
        /** DVB subtitles: parsed to cues before they enter the queue (media3 "parse during extraction") */
        val dvbParser: DvbParser? = null
    ) {
        @Volatile var started = !isVideo
        val isMpeg2 = format.sampleMimeType == MimeTypes.VIDEO_MPEG2
        val isText = MimeTypes.getTrackType(format.sampleMimeType) == C.TRACK_TYPE_TEXT
        var aspectCode = -1        // last confirmed aspect_ratio_information
        var pendingAspectCode = -1
        var pendingCount = 0
        var aacConfigured = !isAac
    }

    private val tracks = ConcurrentHashMap<Int, Track>()
    private val cueEncoder = CueEncoder()
    private val enabled: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile private var trackGroups = TrackGroupArray.EMPTY
    @Volatile private var prepared = false
    @Volatile private var fatalError: Throwable? = null

    // pvr.hts: packets are ignored while a seek is pending (m_seektime != nullptr)
    @Volatile private var seeking = false
    @Volatile private var seekPositionUs = 0L
    private val writeLock = Any()
    private var job: Job? = null
    private var errorJob: Job? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        errorJob = scope.launch {
            subscription.errorFlow.collect { HtspLog.e("Subscription error: ${it.message}"); fatalError = it }
        }
        job = scope.launch {
            val streams = subscription.streams.first { it.isNotEmpty() }
            buildTracks(streams)
            HtspLog.i("MediaPeriod prepared: ${trackGroups.length} usable tracks of ${streams.size}")
            prepared = true
            callback.onPrepared(this@HtspMediaPeriod)
            subscription.packets.collect { onPacket(it) }
        }
    }

    private fun buildTracks(streams: List<HtspStream>) {
        val groups = ArrayList<TrackGroup>()
        for (stream in streams) {
            val sourceFormat = mapToFormat(stream) ?: continue
            val dvbParser = if (sourceFormat.sampleMimeType == MimeTypes.APPLICATION_DVBSUBS)
                DvbParser(sourceFormat.initializationData) else null
            // Queue / track group carry the format the renderer gets: cues for DVB subtitles
            val format = if (dvbParser != null) sourceFormat.buildUpon()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCodecs(MimeTypes.APPLICATION_DVBSUBS)
                .setCueReplacementBehavior(dvbParser.cueReplacementBehavior)
                .build() else sourceFormat
            val queue = SampleQueue.createWithoutDrm(allocator)
            queue.format(format)
            val isVideo = MimeTypes.isVideo(format.sampleMimeType)
            tracks[stream.index] = Track(stream.index, format, queue, isVideo,
                format.sampleMimeType == MimeTypes.AUDIO_AAC, dvbParser)
            groups.add(TrackGroup(stream.index.toString(), format))
        }
        trackGroups = TrackGroupArray(*groups.toTypedArray())
    }

    /** Called before subscriptionSeek is sent. */
    fun beginSeek() { seeking = true }

    /** Seek failed on the server: accept packets again. */
    fun cancelSeek() { seeking = false }

    private fun onPacket(packet: HtspMuxPacket) {
        synchronized(writeLock) { writePacket(packet) }
    }

    private fun writePacket(packet: HtspMuxPacket) {
        if (seeking) return
        val track = tracks[packet.streamIndex] ?: return
        if (track.index !in enabled) return

        if (!track.started) {
            if (!packet.isKeyframe) return
            track.started = true
        }
        if (track.queue.writeIndex == 0) HtspLog.i("First packet on stream ${track.index} pts ${packet.pts}")
        val timeUs = packet.pts

        var data = packet.payload
        if (track.isAac) data = stripAdts(track, data) ?: return
        if (track.isMpeg2 && packet.isKeyframe) checkMpeg2Aspect(track, data)

        track.dvbParser?.let { parser ->
            writeDvbCues(track, parser, data, timeUs)
            return
        }

        track.queue.sampleData(ParsableByteArray(data), data.size)
        val flags = if (track.isVideo && !packet.isKeyframe) 0 else C.BUFFER_FLAG_KEY_FRAME
        track.queue.sampleMetadata(timeUs, flags, data.size, 0, null)
    }

    /**
     * Same as media3's SubtitleTranscodingTrackOutput: parse the DVB page with DvbParser and
     * write the cues (CueEncoder) as an application/x-media3-cues sample at the packet time.
     */
    private fun writeDvbCues(track: Track, parser: DvbParser, data: ByteArray, timeUs: Long) {
        parser.parse(data, SubtitleParser.OutputOptions.allCues()) { cues ->
            val sampleTimeUs = if (cues.startTimeUs == C.TIME_UNSET) timeUs else timeUs + cues.startTimeUs
            val encoded = cueEncoder.encode(cues.cues, cues.durationUs)
            track.queue.sampleData(ParsableByteArray(encoded), encoded.size)
            track.queue.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, encoded.size, 0, null)
        }
    }

    /**
     * HTSP only reports the aspect at subscriptionStart and a change mid-stream triggers no new
     * one (tvheadend parser_set_stream_vparam compares width/height/duration only). So the
     * sequence header is read here, like ffmpeg does in Kodi. A new value must be seen on two
     * sequence headers in a row (same rule as tvheadend's es_meta_change) to avoid flapping.
     * Only the track info changes; the decoder is not reconfigured.
     */
    private fun checkMpeg2Aspect(track: Track, data: ByteArray) {
        var i = 0
        while (i + 7 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1 &&
                (data[i + 3].toInt() and 0xFF) == 0xB3
            ) {
                // 12 bits width, 12 bits height, 4 bits aspect_ratio_information
                val code = (data[i + 7].toInt() and 0xF0) shr 4
                val (num, den) = MPEG2_ASPECT[code]
                if (num == 0) return
                when {
                    track.aspectCode == -1 -> track.aspectCode = code // first header
                    code == track.aspectCode -> track.pendingCount = 0
                    code == track.pendingAspectCode -> {
                        if (++track.pendingCount >= 2) {
                            track.aspectCode = code
                            track.pendingCount = 0
                            HtspLog.i("MPEG-2 aspect changed on stream ${track.index}: $num:$den")
                            onAspectChanged(track.index, num, den)
                        }
                    }
                    else -> { track.pendingAspectCode = code; track.pendingCount = 1 }
                }
                return
            }
            i++
        }
    }

    /** Tvheadend sends AAC with ADTS headers (pvr.hts aac::Decoder expects them); MediaCodec wants raw frames + csd-0. */
    private fun stripAdts(track: Track, data: ByteArray): ByteArray? {
        val isAdts = data.size > 7 &&
            (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xF0) == 0xF0
        if (!isAdts) return if (track.aacConfigured) data else null

        if (!track.aacConfigured) {
            val b2 = data[2].toInt() and 0xFF
            val b3 = data[3].toInt() and 0xFF
            val asc = AacUtil.buildAudioSpecificConfig(
                ((b2 shr 6) and 0x3) + 1,
                (b2 shr 2) and 0xF,
                ((b2 and 0x1) shl 2) or ((b3 shr 6) and 0x3)
            )
            track.queue.format(track.format.buildUpon().setInitializationData(listOf(asc)).build())
            track.aacConfigured = true
        }
        val headerSize = if ((data[1].toInt() and 0x1) == 1) 7 else 9
        return data.copyOfRange(headerSize, data.size)
    }

    override fun maybeThrowPrepareError() {
        fatalError?.let { throw IOException("HTSP: ${it.message}", it) }
    }

    override fun getTrackGroups(): TrackGroupArray = trackGroups

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        for (i in selections.indices) {
            val old = streams[i] as? HtspSampleStream
            if (old != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                enabled.remove(old.streamIndex)
                streams[i] = null
            }
        }
        for (i in selections.indices) {
            val selection = selections[i] ?: continue
            if (streams[i] != null) continue
            val index = selection.trackGroup.id.toIntOrNull() ?: continue
            HtspLog.i("Selected stream $index (${selection.trackGroup.getFormat(0).sampleMimeType})")
            val track = tracks[index] ?: continue
            streams[i] = HtspSampleStream(index, track.queue)
            streamResetFlags[i] = true
            enabled.add(index)
        }
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        for (index in enabled) {
            tracks[index]?.queue?.discardTo(positionUs, toKeyframe, true)
        }
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    /**
     * The server already skipped (subscriptionSkip); like pvr.hts Flush() the old packets
     * are dropped and video waits for the next keyframe.
     */
    override fun seekToUs(positionUs: Long): Long {
        synchronized(writeLock) {
            for (track in tracks.values) {
                track.queue.reset(/* resetUpstreamFormat= */ false)
                track.started = !track.isVideo
                track.dvbParser?.reset()
            }
            seekPositionUs = positionUs
            seeking = false
        }
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

    override fun getBufferedPositionUs(): Long {
        if (!prepared || enabled.isEmpty()) return seekPositionUs
        var min = Long.MAX_VALUE
        for (index in enabled) {
            val track = tracks[index] ?: continue
            if (track.isText) continue // sparse: subtitles must not hold back buffering
            val largest = track.queue.largestQueuedTimestampUs
            if (largest == Long.MIN_VALUE) return seekPositionUs
            if (largest < min) min = largest
        }
        return if (min == Long.MAX_VALUE) seekPositionUs else maxOf(min, seekPositionUs)
    }

    override fun getNextLoadPositionUs(): Long = getBufferedPositionUs()

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

    override fun isLoading(): Boolean = prepared && fatalError == null

    override fun reevaluateBuffer(positionUs: Long) {}

    /** pvr.hts HTSPDemuxer::AddTVHStream codec names mapped to Android mime types. */
    private fun mapToFormat(stream: HtspStream): Format? {
        val codec = when (stream.type) {
            "MPEG2AUDIO" -> "MP2"
            "MPEGTS" -> "MPEG2VIDEO"
            else -> stream.type
        }
        val mime = when (codec) {
            "H264" -> MimeTypes.VIDEO_H264
            "HEVC" -> MimeTypes.VIDEO_H265
            "MPEG2VIDEO" -> MimeTypes.VIDEO_MPEG2
            "AAC" -> MimeTypes.AUDIO_AAC
            "AC3" -> MimeTypes.AUDIO_AC3
            "EAC3" -> MimeTypes.AUDIO_E_AC3
            "MP2" -> MimeTypes.AUDIO_MPEG_L2
            "DVBSUB" -> MimeTypes.APPLICATION_DVBSUBS
            else -> return null
        }
        if (mime == MimeTypes.APPLICATION_DVBSUBS) {
            // tvheadend sends the PES data without data_identifier / stream_id / end marker,
            // which is what media3's DvbParser expects; init data = composition + ancillary page
            val c = stream.compositionId
            val a = stream.ancillaryId
            return Format.Builder()
                .setId(stream.index.toString())
                .setSampleMimeType(mime)
                .setLanguage(stream.language)
                .setInitializationData(listOf(byteArrayOf(
                    (c shr 8).toByte(), c.toByte(), (a shr 8).toByte(), a.toByte()
                )))
                .build()
        }
        val builder = Format.Builder()
            .setId(stream.index.toString())
            .setSampleMimeType(mime)
            .setLanguage(stream.language)

        if (MimeTypes.isVideo(mime)) {
            // pvr.hts ignores video streams whose details are not known yet
            if (stream.width == 0 || stream.height == 0) return null
            builder.setWidth(stream.width).setHeight(stream.height)
                .setPixelWidthHeightRatio(stream.pixelAspectRatio)
            // pvr.hts: FPS = STREAM_TIME_BASE (1e6) / duration
            if (stream.duration > 0) builder.setFrameRate(1_000_000f / stream.duration)
        } else {
            // pvr.hts defaults: channels 2, rate 48000
            builder.setChannelCount(if (stream.channels > 0) stream.channels else 2)
            builder.setSampleRate(stream.sampleRateHz)
        }
        return builder.build()
    }

    fun release() {
        job?.cancel()
        errorJob?.cancel()
        tracks.values.forEach { it.queue.release() }
        tracks.clear()
        enabled.clear()
    }

    private companion object {
        /** tvheadend parsers.c mpeg2_aspect[] */
        val MPEG2_ASPECT = arrayOf(
            0 to 1, 1 to 1, 4 to 3, 16 to 9, 221 to 100, 0 to 1, 0 to 1, 0 to 1,
            0 to 1, 0 to 1, 0 to 1, 0 to 1, 0 to 1, 0 to 1, 0 to 1, 0 to 1
        )
    }
}

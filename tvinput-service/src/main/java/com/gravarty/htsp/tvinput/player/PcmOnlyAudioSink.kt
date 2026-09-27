package com.gravarty.htsp.tvinput.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Accepts only decoded PCM, so compressed audio (AC3, E-AC3, ...) is never passed through
 * but decoded by MediaCodec. Same effect as limiting the sink to PCM capabilities,
 * without the deprecated DefaultAudioSink.Builder.setAudioCapabilities.
 */
@OptIn(UnstableApi::class)
class PcmOnlyAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) AudioSink.SINK_FORMAT_UNSUPPORTED
        else super.getFormatSupport(format)
}

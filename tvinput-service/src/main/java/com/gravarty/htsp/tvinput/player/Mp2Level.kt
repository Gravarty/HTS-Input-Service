package com.gravarty.htsp.tvinput.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Measured on JVC and PEAQ (after the downmix, all audio decoded by ffmpeg): MP2 (SD) is about
 * 0.9 dB louder than Dolby (HD). MP2 gets this fixed reduction, everything else stays as decoded.
 */
object Mp2Level {
    const val REDUCTION_DB = -0.9f

    /** Stream indexes (Format.id) of the current subscription that carry MP2 audio. */
    @Volatile
    var streamIds: Set<String> = emptySet()
}

/** Lowers PCM by [Mp2Level.REDUCTION_DB] while [reduce] is set; otherwise passes audio through. */
@OptIn(UnstableApi::class)
class Mp2GainProcessor : BaseAudioProcessor() {
    @Volatile
    var reduce = false

    private val factor = 10.0.pow(Mp2Level.REDUCTION_DB / 20.0).toFloat()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
            inputAudioFormat
        else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!reduce) {
            out.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) out.putFloat(inputBuffer.getFloat() * factor)
        } else {
            while (inputBuffer.remaining() >= 2) out.putShort((inputBuffer.getShort() * factor).roundToInt().toShort())
        }
        out.flip()
    }
}

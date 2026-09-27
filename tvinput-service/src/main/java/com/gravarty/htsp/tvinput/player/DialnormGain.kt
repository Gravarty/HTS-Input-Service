package com.gravarty.htsp.tvinput.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Undoes the dialogue normalization a hardware AC3/E-AC3 decoder applies (it attenuates the
 * programme to -31 dBFS dialogue level using the stream's "dialnorm"). Kodi decodes with
 * ffmpeg, which does not apply dialnorm; without this AC3 channels play e.g. 8 dB quieter
 * than MP2 channels (dialnorm -23). Gain = 31 - dialnorm dB; 0 dB for other codecs.
 * The value is set by HtspMediaPeriod from the AC3 frame headers of the playing track.
 */
object DialnormGain {
    @Volatile
    var linear: Float = 1f
        private set

    /** dialnorm 1..31 (dB below full scale); 0 = unknown / not AC3 -> no gain */
    fun setDialnorm(dialnorm: Int) {
        linear = if (dialnorm in 1..31) 10f.pow((31 - dialnorm) / 20f) else 1f
    }

    fun reset() { linear = 1f }

    /** Reads dialnorm from an AC3 (bsid <= 10) or E-AC3 (bsid 11..16) sync frame, or 0. */
    fun parseDialnorm(a: ByteArray, off: Int, len: Int): Int {
        if (len < 8 || (a[off].toInt() and 0xFF) != 0x0B || (a[off + 1].toInt() and 0xFF) != 0x77) return 0
        var pos = (off + 2) * 8
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = a[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        // bsid is at the same position in AC3 and E-AC3 (bits 40..44 after the syncword start)
        val bsid = ((a[off + 5].toInt() and 0xFF) ushr 3)
        return if (bsid <= 10) {
            // A/52 AC3: crc1, fscod, frmsizecod, bsid, bsmod, acmod, [cmixlev] [surmixlev] [dsurmod], lfeon, dialnorm
            bits(16); bits(2); bits(6); bits(5); bits(3)
            val acmod = bits(3)
            if ((acmod and 1) != 0 && acmod != 1) bits(2)
            if ((acmod and 4) != 0) bits(2)
            if (acmod == 2) bits(2)
            bits(1)
            bits(5)
        } else if (bsid in 11..16) {
            // A/52 Annex E: strmtyp, substreamid, frmsiz, fscod, fscod2/numblkscod, acmod, lfeon, bsid, dialnorm
            bits(2); bits(3); bits(11); bits(2); bits(2); bits(3); bits(1); bits(5)
            bits(5)
        } else 0
    }
}

/** Applies [DialnormGain] to 16-bit or float PCM. */
@OptIn(UnstableApi::class)
class DialnormGainProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val gain = DialnormGain.linear
        val out = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.hasRemaining()) {
                val v = (inputBuffer.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(v.toShort())
            }
        } else {
            while (inputBuffer.hasRemaining()) {
                out.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
            }
        }
        out.flip()
    }
}

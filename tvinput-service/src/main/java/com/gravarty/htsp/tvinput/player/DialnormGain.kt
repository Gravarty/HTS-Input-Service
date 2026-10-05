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
 * Hardware Dolby decoders apply the stream's dialnorm (programme level brought to -31 dBFS),
 * MP2 is not changed - measured on the JVC/MTK TV: RTL SD -25.3 dBFS, RTL HD (AC3, dialnorm -23)
 * -33.7 dBFS. To play AC3 at the same level as MP2 (like Kodi/ffmpeg, which ignores dialnorm),
 * hardware-decoded AC3 is raised by 31 - dialnorm dB. ffmpeg output is left unchanged.
 * The value is set by HtspMediaPeriod from the AC3 frame headers of the playing track.
 */
object DialnormGain {
    @Volatile
    private var gain: Float = 1f

    /** true while the ffmpeg decoder plays: ffmpeg does not apply dialnorm, so no gain */
    @Volatile
    var softwareDecoder: Boolean = false

    val linear: Float get() = if (softwareDecoder) 1f else gain

    /** dialnorm 1..31 (dB below full scale); 0 = unknown / not AC3 -> no gain */
    fun setDialnorm(dialnorm: Int) {
        // e.g. dialnorm -23 -> +8 dB
        gain = if (dialnorm in 1..31) 10f.pow((31 - dialnorm) / 20f) else 1f
    }

    fun reset() { gain = 1f }

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

    // TEMPORARY diagnostic: output level (RMS dBFS) every 10 s, error log = visible in release
    private var sumSquares = 0.0
    private var samples = 0L

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
                val f = v / 32768.0
                sumSquares += f * f; samples++
            }
        } else {
            while (inputBuffer.hasRemaining()) {
                val v = (inputBuffer.float * gain).coerceIn(-1f, 1f)
                out.putFloat(v)
                sumSquares += v.toDouble() * v; samples++
            }
        }
        out.flip()

        val window = inputAudioFormat.sampleRate.toLong() * inputAudioFormat.channelCount * 10
        if (window > 0 && samples >= window) {
            val db = 10 * kotlin.math.log10(sumSquares / samples + 1e-12)
            com.gravarty.htsp.provider.HtspLog.e(
                "LEVEL %.1f dBFS (%d ch, gain %.2f, software %b)".format(
                    db, inputAudioFormat.channelCount, gain, DialnormGain.softwareDecoder
                )
            )
            sumSquares = 0.0; samples = 0
        }
    }
}

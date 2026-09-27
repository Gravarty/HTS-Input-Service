package com.gravarty.htsp.tvinput.player

import androidx.annotation.OptIn
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi

/**
 * Downmix of decoded multichannel PCM to stereo for the "force stereo" setting.
 * media3 1.5.1 has default matrices only for mono <-> stereo, so the coefficients are built
 * like ffmpeg's rematrix (used by Kodi's audio engine): centre and surround channels at
 * -3 dB (0.7071), back centre split to both sides, LFE dropped. Not normalized, like Kodi's
 * default "maintain original volume on downmix" (otherwise 5.1 would be ~7.7 dB quieter than
 * stereo channels). Channel order = Android's order for the channel count.
 */
@OptIn(UnstableApi::class)
object StereoDownmix {
    private const val M3DB = 0.70710677f

    // Android channel order per count (AudioFormat masks ExoPlayer uses)
    private const val FL = 0; private const val FR = 1; private const val FC = 2; private const val LFE = 3
    private const val BL = 4; private const val BR = 5; private const val BC = 6; private const val SL = 7
    private const val SR = 8

    private val LAYOUTS = mapOf(
        3 to intArrayOf(FL, FR, FC),
        4 to intArrayOf(FL, FR, BL, BR),
        5 to intArrayOf(FL, FR, FC, BL, BR),
        6 to intArrayOf(FL, FR, FC, LFE, BL, BR),
        7 to intArrayOf(FL, FR, FC, LFE, BL, BR, BC),
        8 to intArrayOf(FL, FR, FC, LFE, BL, BR, SL, SR)
    )

    /** (left, right) contribution of one input channel */
    private fun gains(ch: Int): Pair<Float, Float> = when (ch) {
        FL -> 1f to 0f
        FR -> 0f to 1f
        FC -> M3DB to M3DB
        BL, SL -> M3DB to 0f
        BR, SR -> 0f to M3DB
        BC -> M3DB * M3DB to M3DB * M3DB
        else -> 0f to 0f // LFE
    }

    fun processor(): ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
        for ((count, layout) in LAYOUTS) {
            val l = layout.map { gains(it).first }
            val r = layout.map { gains(it).second }
            // row-major: coefficients[input * 2 + output]
            val coefficients = FloatArray(count * 2)
            for (i in 0 until count) {
                coefficients[i * 2] = l[i]
                coefficients[i * 2 + 1] = r[i]
            }
            putChannelMixingMatrix(ChannelMixingMatrix(count, 2, coefficients))
        }
    }
}

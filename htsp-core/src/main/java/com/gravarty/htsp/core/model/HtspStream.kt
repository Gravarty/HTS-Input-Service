package com.gravarty.htsp.core.model

/**
 * Port of pvr.hts stream descriptor parsing from HTSPDemuxer.cpp
 */
data class HtspStream(
    val index: Int,
    val type: String,
    val language: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val aspectNum: Int = 0,
    val aspectDen: Int = 0,
    val frameRateNum: Int = 0,
    val frameRateDen: Int = 0,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val meta: ByteArray? = null,
    val duration: Int = 0,
    val compositionId: Int = 0,   // DVBSUB (pvr.hts SetSubtitleInfo)
    val ancillaryId: Int = 0
) {
    /**
     * Pixel aspect ratio from tvheadend's display aspect (aspect_num/aspect_den, parsed by
     * tvheadend from the video bitstream). 1.0 if unknown.
     */
    val pixelAspectRatio: Float
        get() = when {
            aspectNum <= 0 || aspectDen <= 0 || width <= 0 || height <= 0 -> 1f
            aspectNum == aspectDen -> 1f // MPEG-2 aspect 1 = square pixels, not a 1:1 picture
            else -> (aspectNum.toFloat() * height) / (aspectDen.toFloat() * width)
        }

    /** HTSP "rate" is tvheadend's sample rate index (es_sri); utils.c sri_to_rate(). pvr.hts default 48000. */
    val sampleRateHz: Int
        get() = if (sampleRate > 0) SAMPLE_RATES[sampleRate and 0xF].takeIf { it > 0 } ?: 48000 else 48000

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HtspStream

        if (index != other.index) return false
        if (type != other.type) return false
        if (language != other.language) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (meta != null) {
            if (other.meta == null) return false
            if (!meta.contentEquals(other.meta)) return false
        } else if (other.meta != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + type.hashCode()
        result = 31 * result + (meta?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000,
            44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000,
            7350, 0, 0, 0
        )

        fun fromMap(map: Map<String, Any>): HtspStream {
            val index = (map["index"] as? Number)?.toInt() ?: 0
            val type = map["type"] as? String ?: "UNKNOWN"
            val language = map["language"] as? String
            val width = (map["width"] as? Number)?.toInt() ?: 0
            val height = (map["height"] as? Number)?.toInt() ?: 0
            val aspectNum = (map["aspect_num"] as? Number)?.toInt() ?: 0
            val aspectDen = (map["aspect_den"] as? Number)?.toInt() ?: 0
            val frameRateNum = (map["framerate_num"] as? Number)?.toInt() ?: 0
            val frameRateDen = (map["framerate_den"] as? Number)?.toInt() ?: 0
            val channels = (map["channels"] as? Number)?.toInt() ?: 0
            val sampleRate = (map["rate"] as? Number)?.toInt() ?: 0
            val meta = map["meta"] as? ByteArray
            val duration = (map["duration"] as? Number)?.toInt() ?: 0
            val compositionId = (map["composition_id"] as? Number)?.toInt() ?: 0
            val ancillaryId = (map["ancillary_id"] as? Number)?.toInt() ?: 0

            return HtspStream(
                index = index,
                type = type,
                language = language,
                width = width,
                height = height,
                aspectNum = aspectNum,
                aspectDen = aspectDen,
                frameRateNum = frameRateNum,
                frameRateDen = frameRateDen,
                channels = channels,
                sampleRate = sampleRate,
                meta = meta,
                duration = duration,
                compositionId = compositionId,
                ancillaryId = ancillaryId
            )
        }
    }
}

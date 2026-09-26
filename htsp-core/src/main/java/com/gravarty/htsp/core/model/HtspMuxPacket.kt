package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

/**
 * Direct port of HTSP 'muxpkt' message from pvr.hts / HTSPDemuxer.cpp
 */
data class HtspMuxPacket(
    val subscriptionId: Long,
    val streamIndex: Int,
    val pts: Long,            // Presentation Time Stamp in microseconds
    val dts: Long,            // Decode Time Stamp in microseconds
    val duration: Long,       // Duration in microseconds
    val isKeyframe: Boolean,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HtspMuxPacket

        if (subscriptionId != other.subscriptionId) return false
        if (streamIndex != other.streamIndex) return false
        if (pts != other.pts) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = subscriptionId.hashCode()
        result = 31 * result + streamIndex
        result = 31 * result + pts.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun fromHtsMessage(msg: HtsMessage): HtspMuxPacket? {
            val subId = msg.getLong("subscriptionId") ?: return null
            val stream = msg.getInt("stream") ?: 0
            val pts = msg.getLong("pts") ?: 0L
            val dts = msg.getLong("dts") ?: pts
            val duration = msg.getLong("duration") ?: 0L

            val frameTypeInt = msg.getInt("frametype")
            val keyframeFlag = msg.getInt("keyframe") ?: 0
            val isIFrame = frameTypeInt == 'I'.code || frameTypeInt == 'i'.code || keyframeFlag != 0

            val payload = msg.getByteArray("payload") ?: return null

            return HtspMuxPacket(
                subscriptionId = subId,
                streamIndex = stream,
                pts = pts,
                dts = dts,
                duration = duration,
                isKeyframe = isIFrame,
                payload = payload
            )
        }
    }
}

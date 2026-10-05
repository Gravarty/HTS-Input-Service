package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsBin
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
    /** View into the received frame (no copy) */
    val payload: HtsBin
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HtspMuxPacket

        if (subscriptionId != other.subscriptionId) return false
        if (streamIndex != other.streamIndex) return false
        if (pts != other.pts) return false
        if (payload.length != other.payload.length) return false
        for (i in 0 until payload.length) {
            if (payload[i] != other.payload[i]) return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = subscriptionId.hashCode()
        result = 31 * result + streamIndex
        result = 31 * result + pts.hashCode()
        result = 31 * result + payload.length
        return result
    }

    companion object {
        /**
         * Fast path for live muxpkt frames. Unlike HtsMessageCodec.decode(), this does not create
         * a Map<String, Any>, String instances for every field, or a ByteBuffer wrapper. The
         * payload still points into the already allocated network frame, exactly like the generic
         * decoder's HtsBin path.
         */
        fun fromHtsPayload(a: ByteArray, offset: Int = 0, length: Int = a.size - offset): HtspMuxPacket? {
            val end = offset + length
            if (offset < 0 || length < 6 || end > a.size) return null

            var p = offset
            var isMuxpkt = false
            var subscriptionId = Long.MIN_VALUE
            var stream = 0
            var pts = 0L
            var dts = Long.MIN_VALUE
            var duration = 0L
            var frameType = -1
            var keyframe = false
            var payload: HtsBin? = null

            while (p + 6 <= end) {
                val type = a[p].toInt() and 0xFF
                val nameLen = a[p + 1].toInt() and 0xFF
                val valueLen = readU32(a, p + 2)
                p += 6

                if (valueLen < 0 || p + nameLen > end) return null
                val valueStart = p + nameLen
                val valueEnd = valueStart + valueLen
                if (valueEnd > end) return null

                when {
                    type == 3 && nameEquals(a, p, nameLen, METHOD) &&
                        valueLen == MUXPKT.size && bytesEqual(a, valueStart, MUXPKT) ->
                        isMuxpkt = true

                    type == 2 && nameEquals(a, p, nameLen, SUBSCRIPTION_ID) ->
                        subscriptionId = readS64(a, valueStart, valueLen)

                    type == 2 && nameEquals(a, p, nameLen, STREAM) ->
                        stream = readS64(a, valueStart, valueLen).toInt()

                    type == 2 && nameEquals(a, p, nameLen, PTS) ->
                        pts = readS64(a, valueStart, valueLen)

                    type == 2 && nameEquals(a, p, nameLen, DTS) ->
                        dts = readS64(a, valueStart, valueLen)

                    type == 2 && nameEquals(a, p, nameLen, DURATION) ->
                        duration = readS64(a, valueStart, valueLen)

                    type == 2 && nameEquals(a, p, nameLen, FRAME_TYPE) ->
                        frameType = readS64(a, valueStart, valueLen).toInt()

                    type == 2 && nameEquals(a, p, nameLen, KEYFRAME) ->
                        keyframe = readS64(a, valueStart, valueLen) != 0L

                    type == 7 && nameEquals(a, p, nameLen, KEYFRAME) ->
                        keyframe = valueLen > 0 && a[valueStart] != 0.toByte()

                    type == 4 && nameEquals(a, p, nameLen, PAYLOAD) ->
                        payload = HtsBin(a, valueStart, valueLen)
                }
                p = valueEnd
            }

            if (!isMuxpkt || subscriptionId == Long.MIN_VALUE || payload == null) return null
            val finalDts = if (dts == Long.MIN_VALUE) pts else dts
            val isIFrame = frameType == 'I'.code || frameType == 'i'.code || keyframe
            return HtspMuxPacket(subscriptionId, stream, pts, finalDts, duration, isIFrame, payload)
        }

        fun fromHtsMessage(msg: HtsMessage): HtspMuxPacket? {
            val subId = msg.getLong("subscriptionId") ?: return null
            val stream = msg.getInt("stream") ?: 0
            val pts = msg.getLong("pts") ?: 0L
            val dts = msg.getLong("dts") ?: pts
            val duration = msg.getLong("duration") ?: 0L

            val frameTypeInt = msg.getInt("frametype")
            val keyframeFlag = msg.getInt("keyframe") ?: 0
            val isIFrame = frameTypeInt == 'I'.code || frameTypeInt == 'i'.code || keyframeFlag != 0

            val payload = msg.getBin("payload") ?: return null

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

        private fun readU32(a: ByteArray, p: Int): Int =
            ((a[p].toInt() and 0xFF) shl 24) or
                ((a[p + 1].toInt() and 0xFF) shl 16) or
                ((a[p + 2].toInt() and 0xFF) shl 8) or
                (a[p + 3].toInt() and 0xFF)

        private fun readS64(a: ByteArray, offset: Int, length: Int): Long {
            var value = 0L
            for (i in offset + length - 1 downTo offset) {
                value = (value shl 8) or (a[i].toLong() and 0xFF)
            }
            return value
        }

        private fun nameEquals(a: ByteArray, offset: Int, length: Int, wanted: ByteArray): Boolean {
            if (length != wanted.size) return false
            for (i in wanted.indices) if (a[offset + i] != wanted[i]) return false
            return true
        }

        private fun bytesEqual(a: ByteArray, offset: Int, wanted: ByteArray): Boolean {
            for (i in wanted.indices) if (a[offset + i] != wanted[i]) return false
            return true
        }

        private val METHOD = "method".toByteArray(Charsets.UTF_8)
        private val MUXPKT = "muxpkt".toByteArray(Charsets.UTF_8)
        private val SUBSCRIPTION_ID = "subscriptionId".toByteArray(Charsets.UTF_8)
        private val STREAM = "stream".toByteArray(Charsets.UTF_8)
        private val PTS = "pts".toByteArray(Charsets.UTF_8)
        private val DTS = "dts".toByteArray(Charsets.UTF_8)
        private val DURATION = "duration".toByteArray(Charsets.UTF_8)
        private val FRAME_TYPE = "frametype".toByteArray(Charsets.UTF_8)
        private val KEYFRAME = "keyframe".toByteArray(Charsets.UTF_8)
        private val PAYLOAD = "payload".toByteArray(Charsets.UTF_8)
    }
}

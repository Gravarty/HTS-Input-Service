package com.gravarty.htsp.core

import com.gravarty.htsp.core.model.HtspMuxPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HtspMuxPacketTest {

    @Test
    fun fastParserMatchesGenericDecoder() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val original = HtsMessage(
            method = "muxpkt",
            fields = linkedMapOf(
                "subscriptionId" to 42L,
                "stream" to 3L,
                "pts" to 1_234_567L,
                "dts" to 1_234_000L,
                "duration" to 40_000L,
                "frametype" to 'I'.code.toLong(),
                "payload" to payload
            )
        )

        val encoded = HtsMessageCodec.encode(original)
        val fast = HtspMuxPacket.fromHtsPayload(encoded)!!
        val generic = HtspMuxPacket.fromHtsMessage(HtsMessageCodec.decode(encoded))!!

        assertEquals(generic.subscriptionId, fast.subscriptionId)
        assertEquals(generic.streamIndex, fast.streamIndex)
        assertEquals(generic.pts, fast.pts)
        assertEquals(generic.dts, fast.dts)
        assertEquals(generic.duration, fast.duration)
        assertEquals(generic.isKeyframe, fast.isKeyframe)
        assertEquals(payload.size, fast.payload.length)
        assertArrayEquals(payload, fast.payload.toByteArray())
    }
}

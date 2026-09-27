package com.gravarty.htsp.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer

class HtsMessageCodecTest {

    @Test
    fun testEncodeAndDecodeRoundtrip() {
        val original = HtsMessage(
            method = "hello",
            seq = 1L,
            fields = mapOf(
                "htspversion" to 28L,
                "clientname" to "pvr.hts-android",
                "binaryData" to byteArrayOf(0x01, 0x02, 0x03, 0x04)
            )
        )

        val frame = HtsMessageCodec.encodeFrame(original)
        assertNotNull(frame)

        val frameBuffer = ByteBuffer.wrap(frame)
        val payloadLength = frameBuffer.int
        assertEquals(frame.size - 4, payloadLength)

        val payloadBuffer = ByteBuffer.wrap(frame, 4, payloadLength)
        val decoded = HtsMessageCodec.decode(payloadBuffer)

        assertEquals("hello", decoded.method)
        assertEquals(1L, decoded.seq)
        assertEquals(28L, decoded.getLong("htspversion"))
        assertEquals("pvr.hts-android", decoded.getString("clientname"))
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), decoded.getByteArray("binaryData"))
    }
}

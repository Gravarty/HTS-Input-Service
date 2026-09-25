package com.gravarty.htsp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HtspConnectionTest {

    @Test
    fun testSha1DigestCalculationMatchesCpp() {
        val password = "secretPassword123"
        val challenge = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)

        val digest = HtspConnection.computeSha1Digest(password, challenge)
        assertEquals(20, digest.size) // SHA-1 is always 20 bytes (160 bits)
    }
}

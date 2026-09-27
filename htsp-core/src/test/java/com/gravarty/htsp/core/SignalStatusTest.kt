package com.gravarty.htsp.core

import com.gravarty.htsp.core.model.SignalStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalStatusTest {

    @Test
    fun testSignalStatusNormalizationFromQualityCpp() {
        val msg1 = HtsMessage(
            fields = mapOf(
                "fe_snr" to 85L,
                "fe_signal" to 90L,
                "fe_status" to "HAS_LOCK"
            )
        )
        val status1 = SignalStatus.fromHtsMessage(msg1)
        assertEquals(85, status1.snr)
        assertEquals(90, status1.signal)

        val msg2 = HtsMessage(
            fields = mapOf(
                "fe_snr" to 65535L,
                "fe_signal" to 32767L,
                "fe_status" to "HAS_LOCK"
            )
        )
        val status2 = SignalStatus.fromHtsMessage(msg2)
        assertEquals(100, status2.snr)
        assertEquals(49, status2.signal)
    }
}

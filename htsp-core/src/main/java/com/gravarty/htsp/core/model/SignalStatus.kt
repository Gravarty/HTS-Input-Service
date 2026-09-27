package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

/**
 * Direct port of pvr.hts 'Quality.cpp' / 'signalStatus' message handling
 */
data class SignalStatus(
    val snr: Int = 0,         // 0..100 %
    val signal: Int = 0,      // 0..100 %
    val ber: Long = 0L,       // Bit Error Rate
    val unc: Long = 0L,       // Uncorrected blocks
    val status: String = ""   // Front-end status string e.g. "HAS_LOCK"
) {
    companion object {
        fun fromHtsMessage(msg: HtsMessage): SignalStatus {
            val rawSnr = msg.getInt("fe_snr") ?: 0
            val rawSignal = msg.getInt("fe_signal") ?: 0
            val ber = msg.getLong("fe_ber") ?: 0L
            val unc = msg.getLong("fe_unc") ?: 0L
            val status = msg.getString("fe_status") ?: ""

            return SignalStatus(
                snr = normalizePercentage(rawSnr),
                signal = normalizePercentage(rawSignal),
                ber = ber,
                unc = unc,
                status = status
            )
        }

        /**
         * Direct port of pvr.hts Quality.cpp normalization logic:
         * Scales 0..65535 raw tuner values down to 0..100 percentage.
         */
        private fun normalizePercentage(valRaw: Int): Int {
            return when {
                valRaw in 0..100 -> valRaw
                valRaw in 101..65535 -> (valRaw * 100) / 65535
                else -> 0
            }
        }
    }
}

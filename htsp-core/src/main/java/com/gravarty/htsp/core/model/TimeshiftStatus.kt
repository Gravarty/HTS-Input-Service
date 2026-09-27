package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

/**
 * pvr.hts HTSPDemuxer::ParseTimeshiftStatus. Times in µs, same timebase as the packets (normts).
 * "full" and "shift" are mandatory (message ignored otherwise); start/end keep their
 * previous value when absent.
 */
data class TimeshiftStatus(
    val full: Boolean = false,
    val shift: Long = 0L,   // distance from live, µs
    val start: Long = 0L,   // oldest position in the server buffer, µs
    val end: Long = 0L      // newest position in the server buffer, µs
) {
    fun update(msg: HtsMessage): TimeshiftStatus? {
        val full = msg.getLong("full") ?: return null
        val shift = msg.getLong("shift") ?: return null
        return TimeshiftStatus(
            full = full != 0L,
            shift = shift,
            start = msg.getLong("start") ?: start,
            end = msg.getLong("end") ?: end
        )
    }
}

package com.gravarty.htsp.core

import java.util.Calendar

/**
 * DVR commands exactly as pvr.hts sends them (Tvheadend.cpp AddTimer / UpdateTimer /
 * DeleteTimer / DeleteRecording, AutoRecordings.cpp, TimeRecordings.cpp).
 * Every call returns true if the server answered "success" = 1.
 */
class HtspDvr(private val connection: HtspConnection) {

    // ---- One-shot timers (dvr entries) ----

    /**
     * pvr.hts AddTimer, TIMER_ONCE_EPG: EPG-based timer (the server takes title, times and
     * channel from the event).
     */
    suspend fun addEpgTimer(
        eventId: Long,
        enabled: Boolean = true,
        startExtra: Long = 0,
        stopExtra: Long = 0,
        removal: Long = 0,
        priority: Long = DEFAULT_PRIORITY
    ): Result = add(
        mutableMapOf<String, Any>("eventId" to eventId),
        enabled, startExtra, stopExtra, removal, priority
    )

    /**
     * pvr.hts AddTimer, TIMER_ONCE_MANUAL. start = 0 means "instant timer": start now.
     */
    suspend fun addManualTimer(
        channelId: Long,
        title: String,
        start: Long,
        stop: Long,
        description: String = "",
        enabled: Boolean = true,
        startExtra: Long = 0,
        stopExtra: Long = 0,
        removal: Long = 0,
        priority: Long = DEFAULT_PRIORITY
    ): Result = add(
        mutableMapOf(
            "title" to title,
            "start" to if (start == 0L) System.currentTimeMillis() / 1000 else start,
            "stop" to stop,
            "channelId" to channelId,
            "description" to description
        ),
        enabled, startExtra, stopExtra, removal, priority
    )

    private suspend fun add(
        m: MutableMap<String, Any>, enabled: Boolean, startExtra: Long, stopExtra: Long,
        removal: Long, priority: Long
    ): Result {
        m["enabled"] = if (enabled) 1L else 0L
        m["startExtra"] = startExtra
        m["stopExtra"] = stopExtra
        m["removal"] = removal
        m["priority"] = priority
        val r = connection.sendRequest("addDvrEntry", m)
        return Result(success(r), r.getLong("id"), r.getString("error"))
    }

    /** pvr.hts UpdateTimer for one-shot timers (full set of fields). start = 0 means now. */
    suspend fun updateTimer(
        id: Long,
        channelId: Long,
        title: String,
        start: Long,
        stop: Long,
        description: String = "",
        enabled: Boolean = true,
        startExtra: Long = 0,
        stopExtra: Long = 0,
        removal: Long = 0,
        priority: Long = DEFAULT_PRIORITY
    ): Boolean = sendDvrUpdate(
        mapOf(
            "id" to id,
            "channelId" to channelId,
            "title" to title,
            "enabled" to if (enabled) 1L else 0L,
            "start" to if (start == 0L) System.currentTimeMillis() / 1000 else start,
            "stop" to stop,
            "description" to description,
            "startExtra" to startExtra,
            "stopExtra" to stopExtra,
            "removal" to removal,
            "priority" to priority
        )
    )

    /** pvr.hts UpdateTimer for timers created by autorec/timerec: only enable/disable. */
    suspend fun setTimerEnabled(id: Long, enabled: Boolean): Boolean =
        sendDvrUpdate(mapOf("id" to id, "enabled" to if (enabled) 1L else 0L))

    /** pvr.hts RenameRecording */
    suspend fun renameRecording(id: Long, title: String): Boolean =
        sendDvrUpdate(mapOf("id" to id, "title" to title))

    /** pvr.hts SetRecordingLifetime */
    suspend fun setRecordingLifetime(id: Long, removal: Long): Boolean =
        sendDvrUpdate(mapOf("id" to id, "removal" to removal))

    /** pvr.hts DeleteTimer: a running recording is stopped, otherwise the timer is cancelled. */
    suspend fun deleteTimer(id: Long, isRecording: Boolean): Boolean =
        sendDvrDelete(id, if (isRecording) "stopDvrEntry" else "cancelDvrEntry")

    suspend fun stopRecording(id: Long): Boolean = sendDvrDelete(id, "stopDvrEntry")

    /** pvr.hts DeleteRecording */
    suspend fun deleteRecording(id: Long): Boolean = sendDvrDelete(id, "deleteDvrEntry")

    private suspend fun sendDvrUpdate(m: Map<String, Any>): Boolean =
        success(connection.sendRequest("updateDvrEntry", m))

    /** pvr.hts SendDvrDelete: waits at least 30 s for the answer. */
    private suspend fun sendDvrDelete(id: Long, method: String): Boolean =
        success(connection.sendRequest(method, mapOf("id" to id), timeoutMs = 30000))

    // ---- Series / EPG-search timers (autorec) ----

    /**
     * pvr.hts AutoRecordings::SendAutorecAddOrUpdate. [id] = null adds, otherwise updates.
     * channelId -1 = any channel. Without [useRegex] the search string is escaped like
     * pvr.hts does when "autorec_use_regex" is off (its default).
     */
    suspend fun sendAutorec(
        id: String?,
        name: String,
        epgSearch: String,
        fulltext: Boolean = false,
        startExtra: Long = 0,
        stopExtra: Long = 0,
        removal: Long = 0,
        channelId: Long = -1,
        daysOfWeek: Long = 0x7F,
        dupDetect: Long = 0,
        priority: Long = DEFAULT_PRIORITY,
        enabled: Boolean = true,
        directory: String = "",
        serieslinkUri: String? = null,
        useRegex: Boolean = false
    ): Boolean {
        val m = mutableMapOf<String, Any>()
        if (id != null) m["id"] = id
        m["name"] = name
        m["title"] = if (useRegex) epgSearch else epgSearch.replace(REGEX_SPECIAL, "\\\\$0")
        m["fulltext"] = if (fulltext) 1L else 0L
        m["startExtra"] = startExtra
        m["stopExtra"] = stopExtra
        m["removal"] = removal
        m["channelId"] = channelId
        m["daysOfWeek"] = daysOfWeek
        m["dupDetect"] = dupDetect
        m["priority"] = priority
        m["enabled"] = if (enabled) 1L else 0L
        if (directory.isNotEmpty() && directory != "/") m["directory"] = directory
        if (serieslinkUri != null) m["serieslinkUri"] = serieslinkUri
        return success(connection.sendRequest(if (id == null) "addAutorecEntry" else "updateAutorecEntry", m))
    }

    suspend fun deleteAutorec(id: String): Boolean =
        success(connection.sendRequest("deleteAutorecEntry", mapOf("id" to id)))

    // ---- Repeating time-based timers (timerec) ----

    /**
     * pvr.hts TimeRecordings::SendTimerecAddOrUpdate. start / stop are unix times, the server
     * gets minutes from local midnight. The title gets "-%F-%R" for the file names, like pvr.hts.
     */
    suspend fun sendTimerec(
        id: String?,
        title: String,
        start: Long,
        stop: Long,
        removal: Long = 0,
        channelId: Long,
        daysOfWeek: Long = 0x7F,
        priority: Long = DEFAULT_PRIORITY,
        enabled: Boolean = true,
        directory: String = ""
    ): Boolean {
        val m = mutableMapOf<String, Any>()
        if (id != null) m["id"] = id
        m["name"] = title
        m["title"] = "$title-%F-%R"
        m["start"] = minutesFromMidnight(start)
        m["stop"] = minutesFromMidnight(stop)
        m["removal"] = removal
        m["channelId"] = channelId
        m["daysOfWeek"] = daysOfWeek
        m["priority"] = priority
        m["enabled"] = if (enabled) 1L else 0L
        if (directory.isNotEmpty() && directory != "/") m["directory"] = directory
        return success(connection.sendRequest(if (id == null) "addTimerecEntry" else "updateTimerecEntry", m))
    }

    suspend fun deleteTimerec(id: String): Boolean =
        success(connection.sendRequest("deleteTimerecEntry", mapOf("id" to id)))

    data class Result(val success: Boolean, val id: Long?, val error: String?)

    companion object {
        /** DVR_PRIO_NORMAL in tvheadend (pvr.hts default priority) */
        const val DEFAULT_PRIORITY = 2L

        /** pvr.hts: R"([-[\]{}()*+?.,\^$|#])" */
        private val REGEX_SPECIAL = Regex("""[-\[\]{}()*+?.,\\^$|#]""")

        private fun success(m: HtsMessage): Boolean = (m.getLong("success") ?: 0L) > 0

        private fun minutesFromMidnight(unixSeconds: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = unixSeconds * 1000 }
            return c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)
        }
    }
}

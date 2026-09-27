package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

/**
 * pvr.hts CTvheadend::ParseRecordingAddOrUpdate / entity::Recording.
 * dvrEntryUpdate only carries changed fields, so [update] keeps the previous values
 * (pvr.hts updates the existing Recording in place).
 */
data class DvrEntry(
    val id: Long,
    val channel: Long = 0,
    val channelName: String = "",
    val start: Long = 0,            // s, scheduled
    val stop: Long = 0,             // s, scheduled
    val startExtra: Long = 0,       // min
    val stopExtra: Long = 0,        // min
    val removal: Long = 0,          // lifetime (days, tvheadend "removal")
    val priority: Long = 0,
    val state: State = State.SCHEDULED,
    val eventId: Long = 0,
    val enabled: Boolean = true,
    val title: String = "",
    val subtitle: String = "",
    val path: String = "",
    val description: String = "",   // "description", else "summary"
    val contentType: Long = 0,
    val timerecId: String = "",
    val autorecId: String = "",
    val image: String = "",
    val fanartImage: String = "",
    val ageRating: Long = 0,
    val playcount: Long = 0,
    val playposition: Long = 0,
    val comment: String = "",
    val error: String = "",
    // last file of a multi-file recording (tvheadend always plays that one)
    val filesStart: Long = 0,
    val filesStop: Long = 0,
    val filesSize: Long = 0,
    val hasVideo: Boolean? = null   // from the file stream info, null = unknown
) {
    /** pvr.hts PVR_TIMER_STATE_* as parsed from "state" */
    enum class State { SCHEDULED, RECORDING, COMPLETED, ERROR }

    /** pvr.hts Recording::IsRecording (completed, aborted, recording, conflict_nok) */
    val isRecording: Boolean get() = state == State.COMPLETED || state == State.RECORDING

    /** pvr.hts Recording::IsTimer (scheduled, recording, conflict_ok) */
    val isTimer: Boolean get() = state == State.SCHEDULED || state == State.RECORDING

    /** pvr.hts GetRecordings: real file times if known, else scheduled + margins (s). */
    val recordingStart: Long get() = if (filesStart > 0) filesStart else start - startExtra * 60
    val recordingStop: Long get() = if (filesStart > 0) {
        if (filesStop > 0) filesStop else stop + stopExtra * 60
    } else stop + stopExtra * 60

    fun update(msg: HtsMessage): DvrEntry {
        var e = copy(
            channel = msg.getLong("channel") ?: channel,
            channelName = msg.getString("channelName") ?: channelName,
            start = msg.getLong("start") ?: start,
            stop = msg.getLong("stop") ?: stop,
            startExtra = msg.getLong("startExtra") ?: startExtra,
            stopExtra = msg.getLong("stopExtra") ?: stopExtra,
            removal = msg.getLong("removal") ?: removal,
            priority = msg.getLong("priority") ?: priority,
            state = msg.getString("state")?.let { parseState(it) } ?: state,
            eventId = msg.getLong("eventId") ?: eventId,
            enabled = msg.getLong("enabled")?.let { it != 0L } ?: enabled,
            title = msg.getString("title") ?: title,
            subtitle = msg.getString("subtitle") ?: subtitle,
            path = msg.getString("path") ?: path,
            description = msg.getString("description") ?: msg.getString("summary") ?: description,
            contentType = msg.getLong("contentType") ?: contentType,
            timerecId = msg.getString("timerecId") ?: timerecId,
            autorecId = msg.getString("autorecId") ?: autorecId,
            image = msg.getString("image") ?: image,
            fanartImage = msg.getString("fanartImage") ?: fanartImage,
            ageRating = msg.getLong("ageRating") ?: ageRating,
            playcount = msg.getLong("playcount") ?: playcount,
            playposition = msg.getLong("playposition") ?: playposition,
            comment = msg.getString("comment") ?: comment,
            error = msg.getString("error") ?: error
        )
        // files: metadata of the file with the latest start
        @Suppress("UNCHECKED_CAST")
        val files = msg.getList("files")?.mapNotNull { it as? Map<String, Any> }
        if (files != null) {
            val last = files.filter { (it["start"] as? Number) != null }
                .maxByOrNull { (it["start"] as Number).toLong() }
            if (last != null) {
                val streams = (last["info"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
                val video = streams?.any { it.containsKey("aspect_num") }
                e = e.copy(
                    filesStart = (last["start"] as? Number)?.toLong() ?: e.filesStart,
                    filesStop = (last["stop"] as? Number)?.toLong() ?: e.filesStop,
                    filesSize = (last["size"] as? Number)?.toLong() ?: e.filesSize,
                    hasVideo = video ?: e.hasVideo
                )
            }
        }
        return e
    }

    companion object {
        fun parseState(s: String): State = when {
            s.contains("scheduled") -> State.SCHEDULED
            s.contains("recording") -> State.RECORDING
            s.contains("completed") -> State.COMPLETED
            else -> State.ERROR // "missed", "invalid"
        }

        fun fromHtsMessage(msg: HtsMessage): DvrEntry = DvrEntry(id = msg.getLong("id") ?: 0L).update(msg)
    }
}

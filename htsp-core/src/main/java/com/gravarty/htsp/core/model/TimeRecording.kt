package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

/** pvr.hts TimeRecordings::ParseTimerecAddOrUpdate (repeating time-based timer). IDs are strings. */
data class TimeRecording(
    val id: String,
    val enabled: Boolean = true,
    val daysOfWeek: Long = 0,
    val removal: Long = 0,
    val priority: Long = 0,
    val start: Long = 0,           // minutes from midnight
    val stop: Long = 0,            // minutes from midnight
    val title: String = "",
    val name: String = "",
    val directory: String = "",
    val owner: String = "",
    val creator: String = "",
    val channel: Long = 0,         // 0 = no channel assigned
    val configId: String = "",
    val comment: String = ""
) {
    fun update(msg: HtsMessage): TimeRecording = copy(
        enabled = msg.getLong("enabled")?.let { it != 0L } ?: enabled,
        daysOfWeek = msg.getLong("daysOfWeek") ?: daysOfWeek,
        removal = msg.getLong("removal") ?: removal,
        priority = msg.getLong("priority") ?: priority,
        start = msg.getLong("start") ?: start,
        stop = msg.getLong("stop") ?: stop,
        title = msg.getString("title") ?: title,
        name = msg.getString("name") ?: name,
        directory = msg.getString("directory") ?: directory,
        owner = msg.getString("owner") ?: owner,
        creator = msg.getString("creator") ?: creator,
        channel = msg.getLong("channel") ?: channel,
        configId = msg.getString("configId") ?: configId,
        comment = msg.getString("comment") ?: comment
    )

    companion object {
        fun fromHtsMessage(msg: HtsMessage): TimeRecording? =
            msg.getString("id")?.let { TimeRecording(id = it).update(msg) }
    }
}

package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

/** pvr.hts AutoRecordings::ParseAutorecAddOrUpdate (series / EPG-search timer). IDs are strings. */
data class AutoRecording(
    val id: String,
    val enabled: Boolean = true,
    val removal: Long = 0,
    val daysOfWeek: Long = 0,
    val priority: Long = 0,
    val start: Long = -1,          // minutes from midnight, -1 = any
    val startWindow: Long = -1,    // minutes from midnight, -1 = any
    val startExtra: Long = 0,
    val stopExtra: Long = 0,
    val dupDetect: Long = 0,
    val title: String = "",        // EPG search string
    val name: String = "",
    val directory: String = "",
    val owner: String = "",
    val creator: String = "",
    val channel: Long = 0,         // 0 = any channel
    val fulltext: Boolean = false,
    val serieslinkUri: String = "",
    val broadcastType: Long = 0,
    val configId: String = "",
    val comment: String = ""
) {
    fun update(msg: HtsMessage): AutoRecording = copy(
        enabled = msg.getLong("enabled")?.let { it != 0L } ?: enabled,
        removal = msg.getLong("removal") ?: removal,
        daysOfWeek = msg.getLong("daysOfWeek") ?: daysOfWeek,
        priority = msg.getLong("priority") ?: priority,
        start = msg.getLong("start") ?: start,
        startWindow = msg.getLong("startWindow") ?: startWindow,
        startExtra = msg.getLong("startExtra") ?: startExtra,
        stopExtra = msg.getLong("stopExtra") ?: stopExtra,
        dupDetect = msg.getLong("dupDetect") ?: dupDetect,
        title = msg.getString("title") ?: title,
        name = msg.getString("name") ?: name,
        directory = msg.getString("directory") ?: directory,
        owner = msg.getString("owner") ?: owner,
        creator = msg.getString("creator") ?: creator,
        channel = msg.getLong("channel") ?: channel,
        fulltext = msg.getLong("fulltext")?.let { it != 0L } ?: fulltext,
        serieslinkUri = msg.getString("serieslinkUri") ?: serieslinkUri,
        broadcastType = msg.getLong("broadcastType") ?: broadcastType,
        configId = msg.getString("configId") ?: configId,
        comment = msg.getString("comment") ?: comment
    )

    companion object {
        fun fromHtsMessage(msg: HtsMessage): AutoRecording? =
            msg.getString("id")?.let { AutoRecording(id = it).update(msg) }
    }
}

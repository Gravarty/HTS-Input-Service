package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

data class DvrEntry(
    val id: Long,
    val channelId: Long,
    val state: String,
    val title: String,
    val startSeconds: Long,
    val stopSeconds: Long,
    val path: String? = null
) {
    companion object {
        fun fromHtsMessage(msg: HtsMessage): DvrEntry {
            return DvrEntry(
                id = msg.getLong("id") ?: 0L,
                channelId = msg.getLong("channelId") ?: 0L,
                state = msg.getString("state") ?: "scheduled",
                title = msg.getString("title") ?: "",
                startSeconds = msg.getLong("start") ?: 0L,
                stopSeconds = msg.getLong("stop") ?: 0L,
                path = msg.getString("path")
            )
        }
    }
}

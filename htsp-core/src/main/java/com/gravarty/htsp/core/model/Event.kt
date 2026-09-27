package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

/**
 * Direct port of pvr.hts 'Event.cpp' entity
 */
data class Event(
    val id: Long,
    val channelId: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val startTimeSeconds: Long,
    val stopTimeSeconds: Long,
    val contentType: Int = 0,
    val imageUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val partNumber: Int? = null
) {
    fun merge(msg: HtsMessage): Event {
        return copy(
            title = msg.getString("title") ?: this.title,
            subtitle = msg.getString("subtitle") ?: this.subtitle,
            description = msg.getString("description") ?: this.description,
            startTimeSeconds = msg.getLong("start") ?: this.startTimeSeconds,
            stopTimeSeconds = msg.getLong("stop") ?: this.stopTimeSeconds,
            contentType = msg.getInt("contentType") ?: this.contentType,
            imageUrl = msg.getString("image") ?: this.imageUrl,
            seasonNumber = msg.getInt("seasonNumber") ?: this.seasonNumber,
            episodeNumber = msg.getInt("episodeNumber") ?: this.episodeNumber,
            partNumber = msg.getInt("partNumber") ?: this.partNumber
        )
    }

    companion object {
        fun fromHtsMessage(msg: HtsMessage): Event {
            return Event(
                id = msg.getLong("eventId") ?: 0L,
                channelId = msg.getLong("channelId") ?: 0L,
                title = msg.getString("title") ?: "",
                subtitle = msg.getString("subtitle"),
                description = msg.getString("description"),
                startTimeSeconds = msg.getLong("start") ?: 0L,
                stopTimeSeconds = msg.getLong("stop") ?: 0L,
                contentType = msg.getInt("contentType") ?: 0,
                imageUrl = msg.getString("image"),
                seasonNumber = msg.getInt("seasonNumber"),
                episodeNumber = msg.getInt("episodeNumber"),
                partNumber = msg.getInt("partNumber")
            )
        }
    }
}

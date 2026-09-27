package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

data class Channel(
    val id: Long,
    val name: String,
    val number: Int,
    val numberMinor: Int = 0,
    val iconUrl: String? = null,
    val tagIds: List<Long> = emptyList(),
    val type: Int = TYPE_OTHER
) {
    fun merge(msg: HtsMessage): Channel {
        val newName = msg.getString("channelName") ?: this.name
        val newNumber = msg.getInt("channelNumber") ?: this.number
        val newNumberMinor = msg.getInt("channelNumberMinor") ?: this.numberMinor
        val newIcon = msg.getString("channelIcon") ?: this.iconUrl
        @Suppress("UNCHECKED_CAST")
        val newTags = (msg.getList("tags") as? List<Number>)?.map { it.toLong() } ?: this.tagIds

        return copy(
            type = parseType(msg) ?: this.type,
            name = newName,
            number = newNumber,
            numberMinor = newNumberMinor,
            iconUrl = newIcon,
            tagIds = newTags
        )
    }

    companion object {
        // pvr.hts HTSPTypes.h
        const val TYPE_OTHER = 0
        const val TYPE_TV = 1
        const val TYPE_RADIO = 2

        /** pvr.hts: every service with "content" sets the type, the last one wins. */
        @Suppress("UNCHECKED_CAST")
        fun parseType(msg: HtsMessage): Int? {
            val services = msg.getList("services") ?: return null
            var type: Int? = null
            for (s in services) {
                val map = s as? Map<String, Any> ?: continue
                (map["content"] as? Number)?.let { type = it.toInt() }
            }
            return type
        }

        fun fromHtsMessage(msg: HtsMessage): Channel {
            val id = msg.getLong("channelId") ?: 0L
            val name = msg.getString("channelName") ?: ""
            val number = msg.getInt("channelNumber") ?: 0
            val numberMinor = msg.getInt("channelNumberMinor") ?: 0
            val icon = msg.getString("channelIcon")
            @Suppress("UNCHECKED_CAST")
            val tags = (msg.getList("tags") as? List<Number>)?.map { it.toLong() } ?: emptyList()

            return Channel(
                id = id,
                name = name,
                number = number,
                numberMinor = numberMinor,
                iconUrl = icon,
                tagIds = tags,
                type = parseType(msg) ?: TYPE_OTHER
            )
        }
    }
}

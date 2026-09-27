package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

data class Tag(
    val id: Long,
    val name: String,
    val members: List<Long> = emptyList()
) {
    companion object {
        fun fromHtsMessage(msg: HtsMessage): Tag {
            val id = msg.getLong("tagId") ?: 0L
            val name = msg.getString("tagName") ?: ""
            @Suppress("UNCHECKED_CAST")
            val members = (msg.getList("members") as? List<Number>)?.map { it.toLong() } ?: emptyList()

            return Tag(id, name, members)
        }
    }
}

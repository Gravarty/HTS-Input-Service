package com.gravarty.htsp.core.model

import com.gravarty.htsp.core.HtsMessage

data class Channel(
    val id: Long,
    val name: String,
    val number: Int,
    val numberMinor: Int = 0,
    val iconUrl: String? = null,
    val tagIds: List<Long> = emptyList(),
    val type: Int = TYPE_OTHER,
    /** Network names of the channel's services (first part of services[].name) */
    val networks: List<String> = emptyList(),
    /** pvr.hts SetCaid: first services[].caid, 0 = free. tvheadend sends 65535 for encrypted services. */
    val caid: Int = 0
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
            networks = parseNetworks(msg) ?: this.networks,
            caid = parseCaid(msg) ?: this.caid,
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

        /**
         * tvheadend htsp_build_channel: services[].name = service_nicename = "network/mux/service"
         * (service.c service_make_nicename0). The network part is matched against the
         * network_type learned from subscriptionStart sourceinfo (HtspNetworkTypes).
         */
        @Suppress("UNCHECKED_CAST")
        fun parseNetworks(msg: HtsMessage): List<String>? {
            val services = msg.getList("services") ?: return null
            return services.mapNotNull { s ->
                val name = (s as? Map<String, Any>)?.get("name") as? String ?: return@mapNotNull null
                val parts = name.split('/')
                if (parts.size >= 3 && parts[0].isNotEmpty()) parts[0] else null
            }.distinct()
        }

        /** pvr.hts Tvheadend::ParseChannelUpdate: the first service with a "caid" sets it. */
        @Suppress("UNCHECKED_CAST")
        fun parseCaid(msg: HtsMessage): Int? {
            val services = msg.getList("services") ?: return null
            var caid = 0
            for (s in services) {
                val map = s as? Map<String, Any> ?: continue
                if (caid == 0) (map["caid"] as? Number)?.let { caid = it.toInt() }
            }
            return caid
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
                type = parseType(msg) ?: TYPE_OTHER,
                networks = parseNetworks(msg) ?: emptyList(),
                caid = parseCaid(msg) ?: 0
            )
        }
    }
}

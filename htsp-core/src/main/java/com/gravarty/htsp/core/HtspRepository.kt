package com.gravarty.htsp.core

import com.gravarty.htsp.core.model.Channel
import com.gravarty.htsp.core.model.DvrEntry
import com.gravarty.htsp.core.model.Event
import com.gravarty.htsp.core.model.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds metadata pushed by enableAsyncMetadata.
 * Events are collected in a plain map during the initial sync and published once
 * on initialSyncCompleted (copying a map with tens of thousands of events per
 * message would be O(n²)).
 */
class HtspRepository {

    private val _channels = MutableStateFlow<Map<Long, Channel>>(emptyMap())
    val channels: StateFlow<Map<Long, Channel>> = _channels.asStateFlow()

    private val _tags = MutableStateFlow<Map<Long, Tag>>(emptyMap())
    val tags: StateFlow<Map<Long, Tag>> = _tags.asStateFlow()

    private val eventStore = HashMap<Long, Event>()
    private val _events = MutableStateFlow<Map<Long, Event>>(emptyMap())
    val events: StateFlow<Map<Long, Event>> = _events.asStateFlow()

    private val _dvrEntries = MutableStateFlow<Map<Long, DvrEntry>>(emptyMap())
    val dvrEntries: StateFlow<Map<Long, DvrEntry>> = _dvrEntries.asStateFlow()

    private val _isInitialSyncCompleted = MutableStateFlow(false)
    val isInitialSyncCompleted: StateFlow<Boolean> = _isInitialSyncCompleted.asStateFlow()

    private var nextUnnumbered = UNNUMBERED_CHANNEL

    @Synchronized
    fun handleAsyncMessage(msg: HtsMessage) {
        when (msg.method) {
            "channelAdd" -> {
                // pvr.hts: channelName and channelNumber are mandatory on add
                if (msg.getString("channelName") == null || msg.getLong("channelNumber") == null) return
                var channel = Channel.fromHtsMessage(msg)
                if (channel.number == 0) channel = channel.copy(number = nextUnnumbered++)
                if (channel.id != 0L) _channels.value = _channels.value + (channel.id to channel)
            }
            "channelUpdate" -> {
                val channelId = msg.getLong("channelId") ?: return
                var updated = _channels.value[channelId]?.merge(msg) ?: Channel.fromHtsMessage(msg)
                if (updated.number == 0) updated = updated.copy(number = nextUnnumbered++)
                if (updated.id != 0L) _channels.value = _channels.value + (updated.id to updated)
            }
            "channelDelete" -> {
                val id = msg.getLong("channelId") ?: return
                _channels.value = _channels.value - id
            }
            "tagAdd", "tagUpdate" -> {
                val tag = Tag.fromHtsMessage(msg)
                if (tag.id != 0L) _tags.value = _tags.value + (tag.id to tag)
            }
            "tagDelete" -> {
                val id = msg.getLong("tagId") ?: return
                _tags.value = _tags.value - id
            }
            "eventAdd" -> {
                val event = Event.fromHtsMessage(msg)
                if (event.id != 0L) {
                    eventStore[event.id] = event
                    publishEventsIfLive()
                }
            }
            "eventUpdate" -> {
                val eventId = msg.getLong("eventId") ?: return
                val updated = eventStore[eventId]?.merge(msg) ?: Event.fromHtsMessage(msg)
                if (updated.id != 0L) {
                    eventStore[updated.id] = updated
                    publishEventsIfLive()
                }
            }
            "eventDelete" -> {
                val id = msg.getLong("eventId") ?: return
                eventStore.remove(id)
                publishEventsIfLive()
            }
            "dvrEntryAdd", "dvrEntryUpdate" -> {
                val dvr = DvrEntry.fromHtsMessage(msg)
                if (dvr.id != 0L) _dvrEntries.value = _dvrEntries.value + (dvr.id to dvr)
            }
            "dvrEntryDelete" -> {
                val id = msg.getLong("id") ?: return
                _dvrEntries.value = _dvrEntries.value - id
            }
            "initialSyncCompleted" -> {
                _events.value = HashMap(eventStore)
                _isInitialSyncCompleted.value = true
            }
        }
    }

    private fun publishEventsIfLive() {
        if (_isInitialSyncCompleted.value) _events.value = HashMap(eventStore)
    }

    @Synchronized
    fun getEvents(ids: Collection<Long>): List<Event> = ids.mapNotNull { eventStore[it] }

    @Synchronized
    fun clear() {
        nextUnnumbered = UNNUMBERED_CHANNEL
        _channels.value = emptyMap()
        _tags.value = emptyMap()
        eventStore.clear()
        _events.value = emptyMap()
        _dvrEntries.value = emptyMap()
        _isInitialSyncCompleted.value = false
    }

    companion object {
        /** pvr.hts Tvheadend.h */
        const val UNNUMBERED_CHANNEL = 10000
    }
}

package com.gravarty.htsp.core

import com.gravarty.htsp.core.model.Channel
import com.gravarty.htsp.core.model.AutoRecording
import com.gravarty.htsp.core.model.DvrEntry
import com.gravarty.htsp.core.model.TimeRecording
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
    // No published copy of the whole EPG: before, every eventAdd/Update/Delete after the
    // initial sync copied the complete map (tens of thousands of entries) into a new HashMap,
    // only for the full sync to read it. Readers take a snapshot when they need one.

    private val _dvrEntries = MutableStateFlow<Map<Long, DvrEntry>>(emptyMap())
    val dvrEntries: StateFlow<Map<Long, DvrEntry>> = _dvrEntries.asStateFlow()

    private val _autorecEntries = MutableStateFlow<Map<String, AutoRecording>>(emptyMap())
    val autorecEntries: StateFlow<Map<String, AutoRecording>> = _autorecEntries.asStateFlow()

    private val _timerecEntries = MutableStateFlow<Map<String, TimeRecording>>(emptyMap())
    val timerecEntries: StateFlow<Map<String, TimeRecording>> = _timerecEntries.asStateFlow()

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
                }
            }
            "eventUpdate" -> {
                val eventId = msg.getLong("eventId") ?: return
                val updated = eventStore[eventId]?.merge(msg) ?: Event.fromHtsMessage(msg)
                if (updated.id != 0L) {
                    eventStore[updated.id] = updated
                }
            }
            "eventDelete" -> {
                val id = msg.getLong("eventId") ?: return
                eventStore.remove(id)
            }
            "dvrEntryAdd", "dvrEntryUpdate" -> {
                val id = msg.getLong("id") ?: return
                // pvr.hts ParseRecordingAddOrUpdate: ignore recordings without a file (e.g. removed recordings)
                if (msg.getString("error")?.contains("missing") == true) {
                    _dvrEntries.value = _dvrEntries.value - id
                    return
                }
                val dvr = _dvrEntries.value[id]?.update(msg) ?: DvrEntry.fromHtsMessage(msg)
                _dvrEntries.value = _dvrEntries.value + (id to dvr)
            }
            "autorecEntryAdd", "autorecEntryUpdate" -> {
                val id = msg.getString("id") ?: return
                val rec = _autorecEntries.value[id]?.update(msg) ?: AutoRecording.fromHtsMessage(msg) ?: return
                _autorecEntries.value = _autorecEntries.value + (id to rec)
            }
            "autorecEntryDelete" -> {
                val id = msg.getString("id") ?: return
                _autorecEntries.value = _autorecEntries.value - id
            }
            "timerecEntryAdd", "timerecEntryUpdate" -> {
                val id = msg.getString("id") ?: return
                val rec = _timerecEntries.value[id]?.update(msg) ?: TimeRecording.fromHtsMessage(msg) ?: return
                _timerecEntries.value = _timerecEntries.value + (id to rec)
            }
            "timerecEntryDelete" -> {
                val id = msg.getString("id") ?: return
                _timerecEntries.value = _timerecEntries.value - id
            }
            "dvrEntryDelete" -> {
                val id = msg.getLong("id") ?: return
                _dvrEntries.value = _dvrEntries.value - id
            }
            "initialSyncCompleted" -> {
                _isInitialSyncCompleted.value = true
            }
        }
    }

    /**
     * Memory: drop the descriptions (most of the EPG's ~26 MB) after they were written to the
     * TvProvider by the full sync. Live updates restore a missing description from the TvProvider
     * row (HtspSyncManager.applyChanges), so every programme keeps it - same result as keeping
     * it here, like pvr.hts' Schedules.
     */
    @Synchronized
    fun stripEventDescriptions() {
        for (entry in eventStore.entries) {
            if (entry.value.description != null) entry.setValue(entry.value.copy(description = null))
        }
    }

    /** Snapshot of all events (full sync). */
    @Synchronized
    fun eventsSnapshot(): List<Event> = ArrayList(eventStore.values)

    @Synchronized
    fun getEvents(ids: Collection<Long>): List<Event> = ids.mapNotNull { eventStore[it] }

    @Synchronized
    fun clear() {
        nextUnnumbered = UNNUMBERED_CHANNEL
        _channels.value = emptyMap()
        _tags.value = emptyMap()
        eventStore.clear()
        _dvrEntries.value = emptyMap()
        _autorecEntries.value = emptyMap()
        _timerecEntries.value = emptyMap()
        _isInitialSyncCompleted.value = false
    }

    companion object {
        /** pvr.hts Tvheadend.h */
        const val UNNUMBERED_CHANNEL = 10000
    }
}

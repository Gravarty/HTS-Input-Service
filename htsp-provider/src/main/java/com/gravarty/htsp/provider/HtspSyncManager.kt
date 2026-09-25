package com.gravarty.htsp.provider

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspRepository
import com.gravarty.htsp.core.HtspSettings
import com.gravarty.htsp.core.model.Channel
import com.gravarty.htsp.core.model.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes Tvheadend metadata into TvContract.
 * Channels are diff-synced (update / insert / delete) keyed by the HTSP channelId in
 * COLUMN_INTERNAL_PROVIDER_DATA, so channel _IDs stay stable across scans.
 */
class HtspSyncManager(
    private val context: Context,
    private val connection: HtspConnection,
    private val repository: HtspRepository,
    private val settings: HtspSettings
) {

    // pvr.hts GetChannels(): only TV and radio channels, "other" is skipped
    private fun listedChannels() = repository.channels.value.values
        .filter { it.type == Channel.TYPE_TV || it.type == Channel.TYPE_RADIO }

    /** TV channels -> TV input, radio channels -> radio input. Returns htspId -> row id for both. */
    private fun syncBothInputs(channels: List<Channel>): Map<Long, Long> =
        syncChannels(HtspInputs.tv(context), channels.filter { it.type == Channel.TYPE_TV }) +
        syncChannels(HtspInputs.radio(context), channels.filter { it.type == Channel.TYPE_RADIO })

    suspend fun performSync(
        onProgress: suspend (String) -> Unit = {}
    ) = writeLock.withLock { withContext(Dispatchers.IO) {
        val channels = listedChannels()

        onProgress("Updating ${channels.size} channels...")
        val htspToDbId = syncBothInputs(channels)

        val events = repository.events.value.values
        onProgress("Writing ${events.size} EPG events...")
        syncPrograms(htspToDbId, events)

        var logos = 0
        channels.forEachIndexed { i, channel ->
            val dbId = htspToDbId[channel.id] ?: return@forEachIndexed
            val url = channel.iconUrl?.let { HtspLogoFetcher.buildImageUrl(it, settings.host, settings.httpPort, connection.webRoot) }
            if (HtspLogoFetcher.fetchAndStoreLogo(context, dbId, url, settings.username, settings.password)) logos++
            if (i % 25 == 0) onProgress("Loading logos ${i + 1}/${channels.size}...")
        }
        HtspLog.i("Sync done: ${htspToDbId.size} channels, ${events.size} events, $logos logos")
    } }

    /**
     * Live updates while the service is running (pvr.hts receives the same messages
     * and hands them to Kodi one by one). Channels are diffed again if any changed,
     * programs are replaced only for the changed / deleted event IDs.
     */
    suspend fun applyChanges(
        changedChannelIds: Set<Long>,
        changedEventIds: Set<Long>,
        deletedEventIds: Set<Long>
    ) = writeLock.withLock { withContext(Dispatchers.IO) {
        val htspToDbId = if (changedChannelIds.isNotEmpty()) {
            val channels = listedChannels()
            val map = syncBothInputs(channels)
            for (channel in channels) {
                if (channel.id !in changedChannelIds) continue
                val dbId = map[channel.id] ?: continue
                val url = channel.iconUrl?.let { HtspLogoFetcher.buildImageUrl(it, settings.host, settings.httpPort, connection.webRoot) }
                HtspLogoFetcher.fetchAndStoreLogo(context, dbId, url, settings.username, settings.password)
            }
            map
        } else {
            readChannelMap(HtspInputs.tv(context)) + readChannelMap(HtspInputs.radio(context))
        }

        // No selection allowed: read our program rows and match them by event ID
        val touched = (changedEventIds + deletedEventIds).mapTo(HashSet()) { it.toString() }
        val rows = ArrayList<Long>()
        context.contentResolver.query(
            TvContract.Programs.CONTENT_URI,
            arrayOf(TvContract.Programs._ID, TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1) in touched) rows.add(c.getLong(0))
        }
        val removed = deleteRows(rows) { TvContract.buildProgramUri(it) }
        val events = repository.getEvents(changedEventIds)
        val inserted = insertPrograms(htspToDbId, events)
        HtspLog.i(
            "Live update: channels ${changedChannelIds.size}, events changed ${changedEventIds.size} " +
            "(in repo ${events.size}), deleted ${deletedEventIds.size} -> rows removed $removed, inserted $inserted"
        )
    } }

    private fun readChannelMap(inputId: String): Map<Long, Long> {
        val map = HashMap<Long, Long>()
        context.contentResolver.query(
            TvContract.buildChannelsUriForInput(inputId),
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) c.getString(1)?.toLongOrNull()?.let { map[it] = c.getLong(0) }
        }
        return map
    }

    private fun syncChannels(inputId: String, channels: List<Channel>): Map<Long, Long> {
        val resolver = context.contentResolver
        val channelsUri = TvContract.buildChannelsUriForInput(inputId)
        val wanted = channels.associateBy { it.id }

        // Broken DB from earlier builds (e.g. millions of duplicates): one SQL delete instead of a row diff.
        val rowCount = resolver.query(channelsUri, arrayOf(TvContract.Channels._ID), null, null, null)
            ?.use { it.count } ?: 0
        if (rowCount > wanted.size * 2 + 100) {
            HtspLog.i("$rowCount channel rows found, wiping input before sync")
            resolver.delete(channelsUri, null, null)
        }

        val existing = HashMap<Long, Long>() // htspId -> dbId
        val stale = ArrayList<Long>()
        resolver.query(
            channelsUri,
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val dbId = c.getLong(0)
                val htspId = c.getString(1)?.toLongOrNull()
                when {
                    htspId == null || htspId !in wanted -> stale.add(dbId)
                    existing.containsKey(htspId) -> stale.add(dbId) // duplicate
                    else -> existing[htspId] = dbId
                }
            }
        }

        deleteRows(stale) { TvContract.buildChannelUri(it) }

        val result = HashMap<Long, Long>(existing)
        val ops = ArrayList<ContentProviderOperation>()
        val insertOrder = ArrayList<Long>()
        for (channel in channels) {
            val values = HtspChannelMapper.toContentValues(channel, inputId)
            val dbId = existing[channel.id]
            if (dbId != null) {
                ops.add(ContentProviderOperation.newUpdate(TvContract.buildChannelUri(dbId)).withValues(values).build())
            } else {
                ops.add(ContentProviderOperation.newInsert(TvContract.Channels.CONTENT_URI).withValues(values).build())
                insertOrder.add(channel.id)
            }
        }

        var insertIdx = 0
        for (chunk in ops.chunked(100)) {
            val results = resolver.applyBatch(TvContract.AUTHORITY, ArrayList(chunk))
            for (r in results) {
                val uri = r.uri ?: continue
                result[insertOrder[insertIdx++]] = ContentUris.parseId(uri)
            }
        }
        HtspLog.i("Channels: ${existing.size} updated, $insertIdx inserted, ${stale.size} deleted")
        return result
    }

    /**
     * Diff by event ID (COLUMN_INTERNAL_PROVIDER_DATA) instead of delete-all + insert,
     * so Live Channels never sees an empty guide while the sync runs.
     */
    private fun syncPrograms(htspToDbId: Map<Long, Long>, events: Collection<Event>) {
        val resolver = context.contentResolver

        val wanted = LinkedHashMap<String, ContentValues>()
        for (e in events) {
            val dbId = htspToDbId[e.channelId] ?: continue
            if (e.stopTimeSeconds <= e.startTimeSeconds) continue
            wanted[e.id.toString()] = HtspProgramMapper.toContentValues(e, dbId)
        }
        val columns = wanted.values.flatMap { it.keySet() }.toSortedSet()
            .filter { it != TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA }

        val stale = ArrayList<Long>()
        val ops = ArrayList<ContentProviderOperation>()
        val seen = HashSet<String>()
        resolver.query(
            TvContract.Programs.CONTENT_URI,
            arrayOf(TvContract.Programs._ID, TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA) + columns,
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val rowId = c.getLong(0)
                val key = c.getString(1)
                val values = key?.let { wanted[it] }
                if (values == null || !seen.add(key)) { stale.add(rowId); continue } // gone or duplicate
                val changed = columns.withIndex().any { (i, col) ->
                    values.getAsString(col) != c.getString(i + 2)
                }
                if (changed) {
                    ops.add(ContentProviderOperation.newUpdate(TvContract.buildProgramUri(rowId)).withValues(values).build())
                }
            }
        }

        deleteRows(stale) { TvContract.buildProgramUri(it) }
        for (chunk in ops.chunked(200)) resolver.applyBatch(TvContract.AUTHORITY, ArrayList(chunk))
        val inserts = wanted.filterKeys { it !in seen }.values.toList()
        for (chunk in inserts.chunked(200)) {
            resolver.bulkInsert(TvContract.Programs.CONTENT_URI, chunk.toTypedArray())
        }
        HtspLog.i("Full EPG write: ${stale.size} removed, ${ops.size} updated, ${inserts.size} inserted, ${seen.size - ops.size} unchanged")
    }

    /** Returns the number of inserted rows; skipped events are logged. */
    private fun insertPrograms(htspToDbId: Map<Long, Long>, events: Collection<Event>): Int {
        val resolver = context.contentResolver
        var noChannel = 0
        var badTime = 0
        val values = events.mapNotNull { e ->
            val dbId = htspToDbId[e.channelId] ?: run { noChannel++; return@mapNotNull null }
            if (e.stopTimeSeconds <= e.startTimeSeconds) { badTime++; return@mapNotNull null }
            HtspProgramMapper.toContentValues(e, dbId)
        }
        if (noChannel > 0 || badTime > 0) {
            HtspLog.i("Programs skipped: $noChannel without listed channel, $badTime with invalid time")
        }
        // Binder transactions are limited to ~1 MB; one big bulkInsert fails silently.
        var inserted = 0
        for (chunk in values.chunked(200)) {
            inserted += resolver.bulkInsert(TvContract.Programs.CONTENT_URI, chunk.toTypedArray())
        }
        return inserted
    }

    /**
     * TvProvider rejects any selection on channels / programs for apps without
     * ACCESS_ALL_EPG_DATA ("Selection not allowed"), so rows are deleted by their row URI.
     */
    private fun deleteRows(ids: List<Long>, rowUri: (Long) -> android.net.Uri): Int {
        var deleted = 0
        for (chunk in ids.chunked(200)) {
            val ops = chunk.mapTo(ArrayList()) { ContentProviderOperation.newDelete(rowUri(it)).build() }
            deleted += context.contentResolver.applyBatch(TvContract.AUTHORITY, ops).sumOf { it.count ?: 0 }
        }
        return deleted
    }

    companion object {
        /** Setup, background worker and live service must not write at the same time. */
        val writeLock = Mutex()

        fun insertSampleChannels(context: Context, inputId: String) {
            val resolver = context.contentResolver
            val channelValues = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, "1")
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Das Erste HD (HTSP Test)")
                put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, 1)
                put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, "1")
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
            }
            try {
                resolver.insert(TvContract.Channels.CONTENT_URI, channelValues)
            } catch (e: Exception) {
                HtspLog.e("Failed to insert sample channel: ${e.message}")
            }
        }
    }
}

package com.gravarty.htsp.tvinput

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.net.Uri
import com.gravarty.htsp.core.HtspDvr
import com.gravarty.htsp.core.HtspRepository
import com.gravarty.htsp.core.model.DvrEntry
import com.gravarty.htsp.provider.HtspInputs
import com.gravarty.htsp.provider.HtspLog
import com.gravarty.htsp.provider.HtspRecordingMapper
import com.gravarty.htsp.tvinput.HtspDvrContract.Autorec
import com.gravarty.htsp.tvinput.HtspDvrContract.Dvr
import com.gravarty.htsp.tvinput.HtspDvrContract.Timerec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * DVR interface for the TV app: recordings, timers, series timers and time timers of the
 * tvheadend server, read from the live metadata (pvr.hts async messages) and changed with
 * the same HTSP commands pvr.hts sends (see HtspDvr). Columns: HtspDvrContract.
 * Calls block until the server answered; do not call from the main thread.
 */
class HtspDvrProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        LiveMetadataSync.dvrChangeListener = {
            ctx.contentResolver.notifyChange(HtspDvrContract.BASE_URI, null)
        }
        return true
    }

    override fun getType(uri: Uri): String? = when (table(uri)) {
        T_RECORDINGS -> "vnd.android.cursor.dir/vnd.com.gravarty.hts.recording"
        T_TIMERS -> "vnd.android.cursor.dir/vnd.com.gravarty.hts.timer"
        T_AUTORECS -> "vnd.android.cursor.dir/vnd.com.gravarty.hts.autorec"
        T_TIMERECS -> "vnd.android.cursor.dir/vnd.com.gravarty.hts.timerec"
        else -> null
    }

    // ---- query ----

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val table = table(uri) ?: return null
        val itemId = uri.pathSegments.getOrNull(1)
        return dvr { repo, _ ->
            val tvIds = tvChannelMap()
            when (table) {
                T_RECORDINGS, T_TIMERS -> {
                    val c = MatrixCursor(if (table == T_RECORDINGS) RECORDING_COLUMNS else TIMER_COLUMNS)
                    repo.dvrEntries.value.values
                        .filter { if (table == T_RECORDINGS) it.isRecording else it.isTimer }
                        .filter { itemId == null || it.id.toString() == itemId }
                        .sortedBy { it.start }
                        .forEach { c.addRow(dvrRow(it, tvIds, table == T_RECORDINGS)) }
                    c
                }
                T_AUTORECS -> MatrixCursor(AUTOREC_COLUMNS).apply {
                    repo.autorecEntries.value.values.filter { itemId == null || it.id == itemId }.forEach {
                        addRow(arrayOf<Any?>(
                            it.id, it.name, it.title, it.fulltext.i(), it.channel, tvIds[it.channel],
                            it.daysOfWeek, it.start, it.startWindow, it.startExtra, it.stopExtra,
                            it.removal, it.priority, it.dupDetect, it.enabled.i(), it.directory, it.serieslinkUri
                        ))
                    }
                }
                T_TIMERECS -> MatrixCursor(TIMEREC_COLUMNS).apply {
                    repo.timerecEntries.value.values.filter { itemId == null || it.id == itemId }.forEach {
                        addRow(arrayOf<Any?>(
                            it.id, it.name, it.channel, tvIds[it.channel], it.daysOfWeek, it.start,
                            it.stop, it.removal, it.priority, it.enabled.i(), it.directory
                        ))
                    }
                }
                else -> null
            }
        }?.also { it.setNotificationUri(context!!.contentResolver, HtspDvrContract.BASE_URI) }
    }

    private fun dvrRow(e: DvrEntry, tvIds: Map<Long, Long>, recording: Boolean): Array<Any?> {
        val base = arrayOf<Any?>(
            e.id, e.channel, tvIds[e.channel], e.channelName, e.title, e.subtitle, e.description,
            e.start, e.stop, e.startExtra, e.stopExtra, e.removal, e.priority,
            e.state.name.lowercase(), e.enabled.i(), e.eventId,
            e.autorecId.ifEmpty { null }, e.timerecId.ifEmpty { null }, e.contentType, e.error.ifEmpty { null }
        )
        if (!recording) return base
        return arrayOf(
            *base, e.recordingStart, e.recordingStop, e.filesSize, e.playcount, e.playposition,
            HtspRecordingMapper.dataUri(e.id).toString()
        )
    }

    // ---- insert ----

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val v = values ?: ContentValues()
        return dvr { _, dvr ->
            when (table(uri)) {
                T_TIMERS -> {
                    val eventId = v.getAsLong(Dvr.EVENT_ID)
                    val r = if (eventId != null && eventId > 0) {
                        // pvr.hts AddTimer TIMER_ONCE_EPG
                        dvr.addEpgTimer(
                            eventId, v.bool(Dvr.ENABLED, true), v.long(Dvr.START_EXTRA, 0),
                            v.long(Dvr.STOP_EXTRA, 0), v.long(Dvr.REMOVAL, 0),
                            v.long(Dvr.PRIORITY, HtspDvr.DEFAULT_PRIORITY)
                        )
                    } else {
                        // pvr.hts AddTimer TIMER_ONCE_MANUAL
                        dvr.addManualTimer(
                            channelId(v, Dvr.CHANNEL, Dvr.TV_CHANNEL_ID) ?: return@dvr null,
                            v.getAsString(Dvr.TITLE) ?: "", v.long(Dvr.START, 0), v.long(Dvr.STOP, 0),
                            v.getAsString(Dvr.DESCRIPTION) ?: "", v.bool(Dvr.ENABLED, true),
                            v.long(Dvr.START_EXTRA, 0), v.long(Dvr.STOP_EXTRA, 0), v.long(Dvr.REMOVAL, 0),
                            v.long(Dvr.PRIORITY, HtspDvr.DEFAULT_PRIORITY)
                        )
                    }
                    if (r.success) r.id?.let { ContentUris.withAppendedId(HtspDvrContract.TIMERS_URI, it) }
                        ?: HtspDvrContract.TIMERS_URI
                    else { HtspLog.e("addDvrEntry failed: ${r.error}"); null }
                }
                T_AUTORECS -> if (sendAutorec(dvr, null, v, null)) HtspDvrContract.AUTORECS_URI else null
                T_TIMERECS -> if (sendTimerec(dvr, null, v, null)) HtspDvrContract.TIMERECS_URI else null
                else -> null
            }
        }
    }

    // ---- update ----

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val v = values ?: return 0
        val id = uri.pathSegments.getOrNull(1) ?: return 0
        return dvr { repo, dvr ->
            val ok = when (table(uri)) {
                T_TIMERS -> {
                    val e = repo.dvrEntries.value[id.toLongOrNull()] ?: return@dvr 0
                    val onlyEnabled = v.size() == 1 && v.containsKey(Dvr.ENABLED)
                    if (onlyEnabled && (e.autorecId.isNotEmpty() || e.timerecId.isNotEmpty())) {
                        // pvr.hts UpdateTimer: timer created by autorec/timerec -> enable/disable only
                        dvr.setTimerEnabled(e.id, v.bool(Dvr.ENABLED, e.enabled))
                    } else {
                        dvr.updateTimer(
                            e.id,
                            channelId(v, Dvr.CHANNEL, Dvr.TV_CHANNEL_ID) ?: e.channel,
                            v.getAsString(Dvr.TITLE) ?: e.title,
                            v.long(Dvr.START, e.start), v.long(Dvr.STOP, e.stop),
                            v.getAsString(Dvr.DESCRIPTION) ?: e.description,
                            v.bool(Dvr.ENABLED, e.enabled),
                            v.long(Dvr.START_EXTRA, e.startExtra), v.long(Dvr.STOP_EXTRA, e.stopExtra),
                            v.long(Dvr.REMOVAL, e.removal), v.long(Dvr.PRIORITY, e.priority)
                        )
                    }
                }
                T_RECORDINGS -> {
                    val rid = id.toLongOrNull() ?: return@dvr 0
                    var ok = true
                    v.getAsString(Dvr.TITLE)?.let { ok = ok && dvr.renameRecording(rid, it) }          // pvr.hts RenameRecording
                    v.getAsLong(Dvr.REMOVAL)?.let { ok = ok && dvr.setRecordingLifetime(rid, it) }    // SetRecordingLifetime
                    ok
                }
                T_AUTORECS -> sendAutorec(dvr, id, v, repo)
                T_TIMERECS -> sendTimerec(dvr, id, v, repo)
                else -> false
            }
            if (ok) 1 else 0
        } ?: 0
    }

    // ---- delete ----

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = uri.pathSegments.getOrNull(1) ?: return 0
        return dvr { repo, dvr ->
            val ok = when (table(uri)) {
                // pvr.hts DeleteTimer: running recording -> stopDvrEntry, else cancelDvrEntry
                T_TIMERS -> {
                    val e = repo.dvrEntries.value[id.toLongOrNull()] ?: return@dvr 0
                    dvr.deleteTimer(e.id, e.state == DvrEntry.State.RECORDING)
                }
                T_RECORDINGS -> id.toLongOrNull()?.let { dvr.deleteRecording(it) } ?: false
                T_AUTORECS -> dvr.deleteAutorec(id)
                T_TIMERECS -> dvr.deleteTimerec(id)
                else -> false
            }
            if (ok) 1 else 0
        } ?: 0
    }

    // ---- helpers ----

    private suspend fun sendAutorec(dvr: HtspDvr, id: String?, v: ContentValues, repo: HtspRepository?): Boolean {
        val e = id?.let { repo?.autorecEntries?.value?.get(it) }
        val search = v.getAsString(Autorec.EPG_SEARCH)
        return dvr.sendAutorec(
            id = id,
            name = v.getAsString(Autorec.NAME) ?: e?.name ?: "",
            // unchanged search string is re-sent as stored on the server (no second escaping)
            epgSearch = search ?: e?.title ?: "",
            fulltext = v.bool(Autorec.FULLTEXT, e?.fulltext ?: false),
            startExtra = v.long(Autorec.START_EXTRA, e?.startExtra ?: 0),
            stopExtra = v.long(Autorec.STOP_EXTRA, e?.stopExtra ?: 0),
            removal = v.long(Autorec.REMOVAL, e?.removal ?: 0),
            channelId = channelId(v, Autorec.CHANNEL, Autorec.TV_CHANNEL_ID) ?: e?.channel?.takeIf { it > 0 } ?: -1,
            daysOfWeek = v.long(Autorec.DAYS_OF_WEEK, e?.daysOfWeek ?: 0x7F),
            dupDetect = v.long(Autorec.DUP_DETECT, e?.dupDetect ?: 0),
            priority = v.long(Autorec.PRIORITY, e?.priority ?: HtspDvr.DEFAULT_PRIORITY),
            enabled = v.bool(Autorec.ENABLED, e?.enabled ?: true),
            directory = v.getAsString(Autorec.DIRECTORY) ?: e?.directory ?: "",
            serieslinkUri = v.getAsString(Autorec.SERIESLINK_URI) ?: e?.serieslinkUri?.ifEmpty { null },
            useRegex = if (search == null) true else v.bool(Autorec.USE_REGEX, false)
        )
    }

    private suspend fun sendTimerec(dvr: HtspDvr, id: String?, v: ContentValues, repo: HtspRepository?): Boolean {
        val e = id?.let { repo?.timerecEntries?.value?.get(it) }
        return dvr.sendTimerec(
            id = id,
            title = v.getAsString(Timerec.NAME) ?: e?.name ?: "",
            start = v.getAsLong(Timerec.START) ?: e?.start?.let { todayAt(it) } ?: 0,
            stop = v.getAsLong(Timerec.STOP) ?: e?.stop?.let { todayAt(it) } ?: 0,
            removal = v.long(Timerec.REMOVAL, e?.removal ?: 0),
            channelId = channelId(v, Timerec.CHANNEL, Timerec.TV_CHANNEL_ID) ?: e?.channel ?: 0,
            daysOfWeek = v.long(Timerec.DAYS_OF_WEEK, e?.daysOfWeek ?: 0x7F),
            priority = v.long(Timerec.PRIORITY, e?.priority ?: HtspDvr.DEFAULT_PRIORITY),
            enabled = v.bool(Timerec.ENABLED, e?.enabled ?: true),
            directory = v.getAsString(Timerec.DIRECTORY) ?: e?.directory ?: ""
        )
    }

    /** HTSP channel id from the HTSP column or from a TvContract channel id. */
    private fun channelId(v: ContentValues, htspKey: String, tvKey: String): Long? {
        v.getAsLong(htspKey)?.let { return it }
        val tvId = v.getAsLong(tvKey) ?: return null
        return tvChannelMap().entries.firstOrNull { it.value == tvId }?.key
    }

    /** HTSP channel id -> TvContract channel _ID (both inputs). */
    private fun tvChannelMap(): Map<Long, Long> {
        val map = HashMap<Long, Long>()
        val ctx = context ?: return map
        for (input in listOf(HtspInputs.tv(ctx), HtspInputs.radio(ctx))) {
            ctx.contentResolver.query(
                TvContract.buildChannelsUriForInput(input),
                arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
                null, null, null
            )?.use { c -> while (c.moveToNext()) c.getString(1)?.toLongOrNull()?.let { map[it] = c.getLong(0) } }
        }
        return map
    }

    private fun <T> dvr(block: suspend (HtspRepository, HtspDvr) -> T): T? {
        val ctx = context ?: return null
        return try {
            runBlocking(Dispatchers.IO) { LiveMetadataSync.withDvr(ctx, block = block) }
        } catch (e: Exception) {
            HtspLog.e("DVR provider call failed", e); null
        }
    }

    private fun table(uri: Uri): String? = uri.pathSegments.firstOrNull()
        ?.takeIf { it in setOf(T_RECORDINGS, T_TIMERS, T_AUTORECS, T_TIMERECS) }

    private fun Boolean.i() = if (this) 1 else 0
    private fun ContentValues.long(key: String, def: Long) = getAsLong(key) ?: def
    private fun ContentValues.bool(key: String, def: Boolean) = getAsInteger(key)?.let { it != 0 } ?: def

    /** minutes from midnight -> unix time today (timerec resend) */
    private fun todayAt(minutes: Long): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, (minutes / 60).toInt()); set(Calendar.MINUTE, (minutes % 60).toInt())
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis / 1000

    private companion object {
        const val T_RECORDINGS = "recordings"
        const val T_TIMERS = "timers"
        const val T_AUTORECS = "autorecs"
        const val T_TIMERECS = "timerecs"

        val TIMER_COLUMNS = arrayOf(
            Dvr.ID, Dvr.CHANNEL, Dvr.TV_CHANNEL_ID, Dvr.CHANNEL_NAME, Dvr.TITLE, Dvr.SUBTITLE, Dvr.DESCRIPTION,
            Dvr.START, Dvr.STOP, Dvr.START_EXTRA, Dvr.STOP_EXTRA, Dvr.REMOVAL, Dvr.PRIORITY,
            Dvr.STATE, Dvr.ENABLED, Dvr.EVENT_ID, Dvr.AUTOREC_ID, Dvr.TIMEREC_ID, Dvr.CONTENT_TYPE, Dvr.ERROR
        )
        val RECORDING_COLUMNS = TIMER_COLUMNS + arrayOf(
            Dvr.RECORDING_START, Dvr.RECORDING_STOP, Dvr.FILE_SIZE, Dvr.PLAYCOUNT, Dvr.PLAYPOSITION, Dvr.DATA_URI
        )
        val AUTOREC_COLUMNS = arrayOf(
            Autorec.ID, Autorec.NAME, Autorec.EPG_SEARCH, Autorec.FULLTEXT, Autorec.CHANNEL, Autorec.TV_CHANNEL_ID,
            Autorec.DAYS_OF_WEEK, Autorec.START, Autorec.START_WINDOW, Autorec.START_EXTRA, Autorec.STOP_EXTRA,
            Autorec.REMOVAL, Autorec.PRIORITY, Autorec.DUP_DETECT, Autorec.ENABLED, Autorec.DIRECTORY,
            Autorec.SERIESLINK_URI
        )
        val TIMEREC_COLUMNS = arrayOf(
            Timerec.ID, Timerec.NAME, Timerec.CHANNEL, Timerec.TV_CHANNEL_ID, Timerec.DAYS_OF_WEEK, Timerec.START,
            Timerec.STOP, Timerec.REMOVAL, Timerec.PRIORITY, Timerec.ENABLED, Timerec.DIRECTORY
        )
    }
}

package com.gravarty.htsp.tvinput

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import com.gravarty.htsp.provider.HtspInputs
import com.gravarty.htsp.provider.HtspLog
import com.gravarty.htsp.provider.HtspRecordingMapper
import com.gravarty.htsp.provider.HtspSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * TIF recording session mapped to a recording on the tvheadend server (pvr.hts AddTimer /
 * DeleteTimer): the file is written by the server, not on the TV.
 * - With a program URI: EPG timer (TIMER_ONCE_EPG, "eventId").
 * - Without: instant manual timer (TIMER_ONCE_MANUAL, start = now). The end time is
 *   now + 120 min, Kodi's default "instant recording duration".
 * Stop = stopDvrEntry; the recording is then handed back as a RecordedPrograms row.
 */
class HtspRecordingSession(
    private val context: Context,
    private val inputId: String
) : TvInputService.RecordingSession(context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var channelUri: Uri? = null
    private var htspChannelId: Long? = null
    private var channelName: String = ""
    @Volatile private var dvrId: Long? = null

    override fun onTune(channelUri: Uri) {
        this.channelUri = channelUri
        try {
            context.contentResolver.query(
                channelUri,
                arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, TvContract.Channels.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    htspChannelId = c.getString(0)?.toLongOrNull()
                    channelName = c.getString(1) ?: ""
                }
            }
        } catch (e: Exception) {
            HtspLog.e("Recording tune: channel query failed", e)
        }
        if (htspChannelId == null) notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN)
        else notifyTuned(channelUri)
    }

    override fun onStartRecording(programUri: Uri?) {
        val channelId = htspChannelId ?: return notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN)
        val eventId = programUri?.let { programEventId(it) }

        scope.launch {
            val result = LiveMetadataSync.withDvr(context) { _, dvr ->
                if (eventId != null) {
                    dvr.addEpgTimer(eventId)
                } else {
                    val now = System.currentTimeMillis() / 1000
                    dvr.addManualTimer(
                        channelId = channelId,
                        title = channelName,
                        start = 0, // instant timer: server starts now
                        stop = now + INSTANT_RECORDING_SECONDS
                    )
                }
            }
            if (result == null || !result.success || result.id == null) {
                HtspLog.e("addDvrEntry failed: ${result?.error}")
                notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN)
                return@launch
            }
            dvrId = result.id
            HtspLog.i("Recording started on server: dvr ${result.id} (event $eventId)")
        }
    }

    override fun onStopRecording() {
        val id = dvrId ?: return notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN)
        scope.launch {
            val ok = LiveMetadataSync.withDvr(context) { repo, dvr ->
                val stopped = dvr.stopRecording(id)
                if (stopped) writeRecordedProgram(repo.dvrEntries.value[id], id) else null
            }
            if (ok == null) {
                notifyError(TvInputManager.RECORDING_ERROR_UNKNOWN)
                return@launch
            }
            notifyRecordingStopped(ok)
        }
    }

    override fun onRelease() {
        scope.cancel()
    }

    /** Event ID of a TvContract program (written as COLUMN_INTERNAL_PROVIDER_DATA). */
    private fun programEventId(programUri: Uri): Long? = try {
        context.contentResolver.query(
            programUri, arrayOf(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.toLongOrNull() else null }
    } catch (e: Exception) {
        HtspLog.e("Program query failed", e); null
    }

    /**
     * Returns the RecordedPrograms row of the recording: the one the live sync already wrote,
     * otherwise a new row (the next sync keeps it, it is matched by dvr ID).
     */
    private suspend fun writeRecordedProgram(
        entry: com.gravarty.htsp.core.model.DvrEntry?, id: Long
    ): Uri? = HtspSyncManager.writeLock.withLock {
        val resolver = context.contentResolver
        resolver.query(
            TvContract.RecordedPrograms.CONTENT_URI,
            arrayOf(TvContract.RecordedPrograms._ID, TvContract.RecordedPrograms.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == id.toString()) return@withLock TvContract.buildRecordedProgramUri(c.getLong(0))
            }
        }
        val e = entry ?: return@withLock null
        val channelDbId = channelUri?.lastPathSegment?.toLongOrNull()
        val input = if (inputId == HtspInputs.radio(context)) HtspInputs.radio(context) else HtspInputs.tv(context)
        resolver.insert(TvContract.RecordedPrograms.CONTENT_URI, HtspRecordingMapper.toContentValues(e, input, channelDbId))
    }

    private companion object {
        /** Kodi default "instant recording duration": 120 min */
        const val INSTANT_RECORDING_SECONDS = 120L * 60
    }
}

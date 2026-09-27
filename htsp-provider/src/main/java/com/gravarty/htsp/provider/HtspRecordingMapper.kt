package com.gravarty.htsp.provider

import android.content.ContentValues
import android.media.tv.TvContract
import android.net.Uri
import com.gravarty.htsp.core.model.DvrEntry

/**
 * Recording -> TvContract.RecordedPrograms, with the fields pvr.hts CTvheadend::GetRecordings
 * reports to Kodi (title, subtitle, plot, genre, recording time / duration, size).
 */
object HtspRecordingMapper {
    private const val SCHEME = "htsp"
    private const val AUTHORITY = "dvr"

    /** RECORDING_DATA_URI of a recording, e.g. htsp://dvr/123 */
    fun dataUri(recordingId: Long): Uri = Uri.Builder().scheme(SCHEME).authority(AUTHORITY)
        .appendPath(recordingId.toString()).build()

    fun recordingIdFromDataUri(uri: Uri?): Long? =
        if (uri?.scheme == SCHEME && uri.authority == AUTHORITY) uri.lastPathSegment?.toLongOrNull() else null

    fun toContentValues(rec: DvrEntry, inputId: String, channelDbId: Long?): ContentValues =
        ContentValues().apply {
            put(TvContract.RecordedPrograms.COLUMN_INPUT_ID, inputId)
            if (channelDbId != null) put(TvContract.RecordedPrograms.COLUMN_CHANNEL_ID, channelDbId)
            put(TvContract.RecordedPrograms.COLUMN_TITLE, rec.title)
            if (rec.subtitle.isNotEmpty()) put(TvContract.RecordedPrograms.COLUMN_EPISODE_TITLE, rec.subtitle)
            if (rec.description.isNotEmpty()) put(TvContract.RecordedPrograms.COLUMN_SHORT_DESCRIPTION, rec.description)
            DvbGenreMapper.getCanonicalGenre(rec.contentType.toInt())?.let {
                put(TvContract.RecordedPrograms.COLUMN_CANONICAL_GENRE, it)
            }
            val start = rec.recordingStart
            val stop = rec.recordingStop
            put(TvContract.RecordedPrograms.COLUMN_START_TIME_UTC_MILLIS, start * 1000)
            put(TvContract.RecordedPrograms.COLUMN_END_TIME_UTC_MILLIS, stop * 1000)
            put(TvContract.RecordedPrograms.COLUMN_RECORDING_DURATION_MILLIS, (stop - start) * 1000)
            put(TvContract.RecordedPrograms.COLUMN_RECORDING_DATA_URI, dataUri(rec.id).toString())
            if (rec.filesSize > 0) put(TvContract.RecordedPrograms.COLUMN_RECORDING_DATA_BYTES, rec.filesSize)
            put(TvContract.RecordedPrograms.COLUMN_INTERNAL_PROVIDER_DATA, rec.id.toString())
        }
}

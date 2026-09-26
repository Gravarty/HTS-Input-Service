package com.gravarty.htsp.provider

import android.content.ContentValues
import android.media.tv.TvContract
import com.gravarty.htsp.core.model.Event

object HtspProgramMapper {
    fun toContentValues(event: Event, channelDbId: Long): ContentValues {
        return ContentValues().apply {
            put(TvContract.Programs.COLUMN_CHANNEL_ID, channelDbId)
            put(TvContract.Programs.COLUMN_TITLE, event.title)

            event.subtitle?.let {
                if (it.isNotEmpty()) put(TvContract.Programs.COLUMN_EPISODE_TITLE, it)
            }
            event.description?.let {
                if (it.isNotEmpty()) put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, it)
            }
            put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, event.startTimeSeconds * 1000L)
            put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, event.stopTimeSeconds * 1000L)
            put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA, event.id.toString())

            event.imageUrl?.let {
                if (it.isNotEmpty()) put(TvContract.Programs.COLUMN_POSTER_ART_URI, it)
            }

            event.seasonNumber?.let {
                put(TvContract.Programs.COLUMN_SEASON_DISPLAY_NUMBER, it.toString())
            }

            event.episodeNumber?.let {
                put(TvContract.Programs.COLUMN_EPISODE_DISPLAY_NUMBER, it.toString())
            }

            DvbGenreMapper.getCanonicalGenre(event.contentType)?.let { canonicalGenre ->
                put(TvContract.Programs.COLUMN_CANONICAL_GENRE, canonicalGenre)
            }
        }
    }
}

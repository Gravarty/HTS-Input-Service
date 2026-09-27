package com.gravarty.htsp.provider

import android.media.tv.TvContract

object DvbGenreMapper {

    /**
     * Translates DVB EN 300 468 Content Nibble (from Tvheadend contentType field)
     * into Android TvContract.Programs.Genres canonical genre string array.
     */
    fun getCanonicalGenre(contentType: Int): String? {
        val mainGenreNibble = contentType and 0xF0
        val genre = when (mainGenreNibble) {
            0x10 -> TvContract.Programs.Genres.MOVIES
            0x20 -> TvContract.Programs.Genres.NEWS
            0x30 -> TvContract.Programs.Genres.ENTERTAINMENT
            0x40 -> TvContract.Programs.Genres.SPORTS
            0x50 -> TvContract.Programs.Genres.FAMILY_KIDS
            0x60 -> TvContract.Programs.Genres.MUSIC
            0x70 -> TvContract.Programs.Genres.ARTS
            0x80 -> TvContract.Programs.Genres.NEWS
            0x90 -> TvContract.Programs.Genres.TECH_SCIENCE
            0xA0 -> TvContract.Programs.Genres.LIFE_STYLE
            else -> null
        }

        return genre?.let { TvContract.Programs.Genres.encode(it) }
    }
}

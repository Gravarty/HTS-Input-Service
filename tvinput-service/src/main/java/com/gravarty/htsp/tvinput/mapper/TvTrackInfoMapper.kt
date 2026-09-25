package com.gravarty.htsp.tvinput.mapper

import android.media.tv.TvTrackInfo
import com.gravarty.htsp.core.model.HtspStream

object TvTrackInfoMapper {

    fun toTvTrackInfoList(streams: List<HtspStream>): List<TvTrackInfo> {
        val result = mutableListOf<TvTrackInfo>()

        for (stream in streams) {
            val trackId = stream.index.toString()
            val lang = stream.language ?: "und"

            val trackInfo = when (stream.type.uppercase()) {
                "H264", "HEVC", "MPEG2VIDEO", "VP8", "VP9", "AV1" -> {
                    TvTrackInfo.Builder(TvTrackInfo.TYPE_VIDEO, trackId)
                        .setLanguage(lang)
                        .setVideoWidth(stream.width)
                        .setVideoHeight(stream.height)
                        .setVideoPixelAspectRatio(stream.pixelAspectRatio)
                        .build()
                }
                "AAC", "AC3", "EAC3", "MPEG2AUDIO", "VORBIS", "FLAC", "OPUS" -> {
                    TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, trackId)
                        .setLanguage(lang)
                        .setAudioChannelCount(stream.channels)
                        .setAudioSampleRate(stream.sampleRateHz)
                        .build()
                }
                // TEXTSUB / TELETEXT have no decoder here, so they are not offered
                "DVBSUB" -> {
                    TvTrackInfo.Builder(TvTrackInfo.TYPE_SUBTITLE, trackId)
                        .setLanguage(lang)
                        .build()
                }
                else -> null
            }

            if (trackInfo != null) {
                result.add(trackInfo)
            }
        }

        return result
    }
}

package com.gravarty.htsp.provider

import android.content.ContentValues
import android.media.tv.TvContract
import com.gravarty.htsp.core.model.Channel

object HtspChannelMapper {
    fun toContentValues(channel: Channel, inputId: String): ContentValues {
        val number = if (channel.numberMinor > 0) "${channel.number}.${channel.numberMinor}"
                     else channel.number.toString()
        val serviceType = if (channel.type == Channel.TYPE_RADIO) TvContract.Channels.SERVICE_TYPE_AUDIO
                          else TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO
        return ContentValues().apply {
            put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
            put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, number)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.name)
            put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, channel.id.toInt())
            put(TvContract.Channels.COLUMN_SERVICE_TYPE, serviceType)
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, channel.id.toString())
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
        }
    }
}

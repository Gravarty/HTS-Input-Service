package com.gravarty.htsp.provider

import android.content.ComponentName
import android.content.Context

/**
 * Two TV inputs in one app: TV and radio channels are kept apart like the TV / Radio
 * sections in Kodi (pvr.hts CHANNEL_TYPE_TV / CHANNEL_TYPE_RADIO). Input ID = the
 * service component, as the TV input framework builds it.
 */
object HtspInputs {
    const val TV_SERVICE = "com.gravarty.htsp.tvinput.HtspTvInputService"
    const val RADIO_SERVICE = "com.gravarty.htsp.tvinput.HtspRadioInputService"

    fun tv(context: Context): String =
        ComponentName(context.packageName, TV_SERVICE).flattenToShortString()

    fun radio(context: Context): String =
        ComponentName(context.packageName, RADIO_SERVICE).flattenToShortString()
}

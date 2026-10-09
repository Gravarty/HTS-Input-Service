package com.gravarty.htsp.provider

import android.content.Context
import android.media.tv.TvContract
import com.gravarty.htsp.core.model.Channel

/**
 * Reception type per tvheadend network (antenna / cable / satellite) for
 * TvContract.Channels.COLUMN_TYPE. HTSP sends it only in subscriptionStart
 * (sourceinfo.network_type, tvheadend mpegts_network_get_type_str: "DVB-T", "DVB-C",
 * "DVB-S", ...), not per channel. So it is learned per network name when a channel plays
 * and applied to every channel whose services[].name starts with that network.
 * Unknown network -> TYPE_OTHER (no guessing). S/S2 and T/T2 are not distinguished by HTSP.
 * The TvProvider accepts COLUMN_TYPE only on insert (an update changing it is rejected), so
 * unknown networks are probed before channel rows are created (HtspSyncManager).
 */
object HtspNetworkTypes {
    private const val PREFS = "htsp_network_types"

    /** Stores network -> network_type. true if this is new or changed (channels must be rewritten). */
    fun learn(context: Context, network: String?, networkType: String?): Boolean {
        if (network.isNullOrEmpty() || networkType.isNullOrEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(network, null) == networkType) return false
        prefs.edit().putString(network, networkType).apply()
        // error level = visible in release builds; logged once per newly learned network
        HtspLog.e("Network '$network' is $networkType")
        return true
    }

    fun isKnown(context: Context, network: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(network)

    fun tvType(context: Context, channel: Channel): String {
        if (channel.networks.isEmpty()) return TvContract.Channels.TYPE_OTHER
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (network in channel.networks) {
            when (prefs.getString(network, null)) {
                "DVB-T" -> return TvContract.Channels.TYPE_DVB_T
                "DVB-C" -> return TvContract.Channels.TYPE_DVB_C
                "DVB-S" -> return TvContract.Channels.TYPE_DVB_S
            }
        }
        return TvContract.Channels.TYPE_OTHER
    }
}

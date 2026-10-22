package com.gravarty.htsp.tvinput

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock
import com.gravarty.htsp.core.HtsMessage

/**
 * Latest tvheadend signalStatus of the playing channel, for the TV app's signal display.
 * Works on every Android version: the TV app queries content://com.gravarty.hts.signal/status
 * (e.g. once a second while its signal panel is open); nothing is sent when nobody asks.
 * Columns = HTSP field names (htsp_server.c htsp_subscription_signal_status), NULL when
 * tvheadend did not send the field, plus age_ms (NULL until the first signalStatus).
 * adapter / mux / service / provider: subscriptionStart sourceinfo like pvr.hts
 * HTSPDemuxer::ParseSourceInfo / CurrentSignal (mux = "satpos: mux").
 * No row = no playback / setting off.
 */
class SignalStatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val latest = SignalStatusStore.latest
        val src = SignalStatusStore.source
        if (latest == null && src == null) return cursor
        val m = latest?.message
        cursor.addRow(arrayOf<Any?>(
            m?.getString("feStatus"),
            m?.getLong("feSNR"), m?.getLong("feAbsoluteSNR"),
            m?.getLong("feSignal"), m?.getLong("feAbsoluteSignal"),
            m?.getLong("feBER"), m?.getLong("feUNC"),
            latest?.let { SystemClock.elapsedRealtime() - it.receivedAt },
            src?.adapter, src?.mux, src?.service, src?.provider
        ))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.gravarty.hts.signal"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        val COLUMNS = arrayOf(
            "feStatus", "feSNR", "feAbsoluteSNR", "feSignal", "feAbsoluteSignal", "feBER", "feUNC", "age_ms",
            "adapter", "mux", "service", "provider"
        )
    }
}

/** Holds only the last signalStatus message; set by the playing session. */
object SignalStatusStore {
    class Entry(val message: HtsMessage, val receivedAt: Long)
    class Source(val adapter: String?, val mux: String?, val service: String?, val provider: String?)

    @Volatile
    var latest: Entry? = null
        private set

    @Volatile
    var source: Source? = null
        private set

    /** pvr.hts HTSPDemuxer::ParseSourceInfo: mux name prefixed with the satellite position. */
    fun setSource(subscriptionStart: HtsMessage) {
        val si = subscriptionStart.getMap("sourceinfo") ?: return
        val satpos = si["satpos"] as? String
        val mux = si["mux"] as? String
        source = Source(
            adapter = si["adapter"] as? String,
            mux = if (satpos == null && mux == null) null else (satpos?.let { "$it: " } ?: "") + (mux ?: ""),
            service = si["service"] as? String,
            provider = si["provider"] as? String
        )
    }

    fun update(message: HtsMessage) {
        latest = Entry(message, SystemClock.elapsedRealtime())
    }

    fun clear() {
        latest = null
        source = null
    }
}

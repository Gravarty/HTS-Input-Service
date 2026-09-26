package com.gravarty.htsp.tvinput

import android.media.tv.TvInputService
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspSettings
import com.gravarty.htsp.provider.HtspServerSync

/** "Tvheadend TV": TV channels. The radio input subclasses this (HtspRadioInputService). */
open class HtspTvInputService : TvInputService() {

    private var connection: HtspConnection? = null   // playback (sessions)
    private var connectionSettings: HtspSettings? = null

    override fun onCreate() {
        super.onCreate()
        getOrCreateConnection()
        LiveMetadataSync.acquire(this)
    }

    /** Recreated when the connection settings were changed in HtspSettingsActivity. */
    fun getOrCreateConnection(): HtspConnection {
        val s = HtspServerSync.loadSettings(this)
        val current = connection
        val old = connectionSettings
        if (current != null && old != null && old.host == s.host && old.port == s.port &&
            old.username == s.username && old.password == s.password
        ) return current
        current?.disconnect()
        val conn = HtspConnection(s.host, s.port, s.username, s.password)
        connection = conn
        connectionSettings = s
        return conn
    }

    override fun onCreateSession(inputId: String): Session? =
        HtspTvInputSession(this) { getOrCreateConnection() }

    override fun onDestroy() {
        LiveMetadataSync.release()
        connection?.disconnect()
        connection = null
        super.onDestroy()
    }
}

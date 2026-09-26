package com.gravarty.htsp.core

/**
 * Direct port of pvr.hts 'Settings.cpp' configuration properties
 */
data class HtspSettings(
    val host: String = "127.0.0.1",
    val port: Int = 9982,
    val username: String = "",
    val password: String = "",
    val profile: String = "",
    val connectTimeoutSeconds: Int = 10,
    val enableEpg: Boolean = true,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val epgMaxTimeSeconds: Long = DEFAULT_EPG_MAX_TIME
) {
    companion object {
        const val PREF_NAME = "htsp_settings"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_HTTP_PORT = "http_port"
        const val DEFAULT_HTTP_PORT = 9981
        /** Seconds as string (ListPreference "epg_max_time" from the old htsptvinput settings) */
        const val KEY_EPG_MAX_TIME = "epg_max_time"
        /** 3 days (Kodi default "EPG future days") */
        const val DEFAULT_EPG_MAX_TIME = 259200L
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PROFILE = "profile"
        const val KEY_TIMEOUT = "connect_timeout"
        const val KEY_ENABLE_EPG = "enable_epg"
    }
}

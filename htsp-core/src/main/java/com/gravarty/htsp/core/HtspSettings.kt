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
        /** Multimedia tunneling (Android TV tunnel mode), boolean, default off (timeshift problems) */
        const val KEY_TUNNELING = "tunneling"
        /** Downmix multichannel audio to stereo, boolean, default on */
        const val KEY_FORCE_STEREO = "force_stereo"
        /** "Recently watched" preview channel on the launcher home screen, boolean, default off */
        const val KEY_HOME_PREVIEW = "home_preview"
        /** Direct packet delivery from the read thread to the player, boolean, default on */
        const val KEY_DIRECT_PACKETS = "direct_packets"
        /** Server errors (no signal, no tuner, scrambled) as TIF reasons, boolean, default off */
        const val KEY_DETAILED_ERRORS = "detailed_errors"
        /** Forward tvheadend signalStatus to the TV app as session event, boolean, default off */
        const val KEY_SIGNAL_STATUS = "signal_status"
        const val KEY_TIMEOUT = "connect_timeout"
        const val KEY_ENABLE_EPG = "enable_epg"
    }
}

package com.gravarty.htsp.provider

import android.util.Log

/** Fixed tag for logcat: adb logcat -s HTSP */
object HtspLog {
    @PublishedApi internal const val TAG = "HTSP"

    /** Debug builds only: gates logging and debug-only work (e.g. the playback statistics). */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /** Info logs only in debug builds; the text is not even built in release. Errors are always logged. */
    inline fun i(msg: () -> String) { if (BuildConfig.DEBUG) Log.i(TAG, msg()) }

    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}

package com.gravarty.htsp.provider

import android.util.Log

/** Fixed tag for logcat: adb logcat -s HTSP */
object HtspLog {
    private const val TAG = "HTSP"
    /** Info logs only in debug builds; errors are always logged. */
    fun i(msg: String) { if (BuildConfig.DEBUG) Log.i(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}

package com.mobyle.nanquim.data.platform

import android.util.Log
import com.mobyle.nanquim.domain.platform.LogSink

/** Sends the domain's log lines to logcat. */
object AndroidLogSink : LogSink {
    override fun warn(
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        Log.w(tag, message, error)
    }

    override fun error(
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        Log.e(tag, message, error)
    }
}

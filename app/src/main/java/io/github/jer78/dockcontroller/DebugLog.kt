package io.github.jer78.dockcontroller

import android.util.Log

// The app's logging, for debugging over adb (logcat). Silent unless Settings
// > Setup > Debug Logging is on (see SonosApplication), so nothing is logged
// the rest of the time.
object DebugLog {
    @Volatile var enabled = false

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (enabled) Log.w(tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (enabled) Log.e(tag, message, error)
    }
}

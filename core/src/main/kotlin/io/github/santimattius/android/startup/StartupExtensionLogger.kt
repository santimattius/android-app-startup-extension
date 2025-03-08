package io.github.santimattius.android.startup

import android.util.Log
import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY)
internal object StartupExtensionLogger {
    private const val TAG = "StartupLogger"

    const val DEBUG: Boolean = false

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun warning(message: String) {
        Log.w(TAG, message)
    }

    fun error(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
    }
}
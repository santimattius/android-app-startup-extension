package io.github.santimattius.android.startup

import android.util.Log
import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY)
internal object StartupExtensionLogger {

    private const val TAG = "StartupLogger"

    @Volatile
    private var delegate: Logger = NoOpLogger

    val isDebugEnabled: Boolean
        get() = delegate !== NoOpLogger

    fun enable(enabled: Boolean) {
        delegate = if (enabled) AndroidLogger else NoOpLogger
    }

    fun info(message: String) = delegate.info(message)

    fun warning(message: String) = delegate.warning(message)

    fun error(message: String, throwable: Throwable) = delegate.error(message, throwable)

    private interface Logger {
        fun info(message: String)
        fun warning(message: String)
        fun error(message: String, throwable: Throwable)
    }

    private object NoOpLogger : Logger {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
    }

    private object AndroidLogger : Logger {
        override fun info(message: String) { Log.i(TAG, message) }
        override fun warning(message: String) { Log.w(TAG, message) }
        override fun error(message: String, throwable: Throwable) { Log.e(TAG, message, throwable) }
    }
}

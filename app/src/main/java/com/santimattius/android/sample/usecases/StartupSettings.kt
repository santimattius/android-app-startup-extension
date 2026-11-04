package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.AppStartupConfig
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.AsyncInitializerStrategy
import io.github.santimattius.android.startup.SyncOrderingStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The sample's own view of the library configuration. `AppStartupInitializer.configure` replaces the
 * WHOLE config and the current one is not readable, so the sample keeps its settings here and always
 * passes every option in one call.
 */
data class StartupSettings(
    val maxConcurrentAsyncInitializers: Int? = null,
    val useIoAsDefaultDispatcher: Boolean = false,
    val topologicalSync: Boolean = false,
    val validatedAsync: Boolean = false,
    val dispatcherInstrumentation: Boolean = false,
    val strictModeCheck: Boolean = false,
    val strictModeConcurrencyThreshold: Int? = null,
    val deferredStartupTimeoutMs: Long? = null,
    val neverSignalFirstFrame: Boolean = false,
    val metricsReplayCapacity: Int? = null,
    val debugLogging: Boolean = false,
) {
    // Not `with(builder)`: the builder has same-named properties that would shadow ours.
    fun applyTo(builder: AppStartupConfig.Builder) {
        val s = this
        builder.maxConcurrentAsyncInitializers = s.maxConcurrentAsyncInitializers
        if (s.useIoAsDefaultDispatcher) builder.defaultAsyncDispatcher = Dispatchers.IO as CoroutineDispatcher
        if (s.topologicalSync) builder.syncOrderingStrategy = SyncOrderingStrategy.Topological
        if (s.validatedAsync) builder.asyncInitializerStrategy = AsyncInitializerStrategy.Validated
        builder.dispatcherInstrumentationEnabled = s.dispatcherInstrumentation
        builder.strictModeCheckEnabled = s.strictModeCheck
        s.strictModeConcurrencyThreshold?.let { builder.strictModeConcurrencyThreshold = it }
        s.deferredStartupTimeoutMs?.let { builder.deferredStartupTimeoutMs = it }
        if (s.neverSignalFirstFrame) builder.firstFrameSignal = NeverFirstFrameSignal
        s.metricsReplayCapacity?.let { builder.metricsReplayCapacity = it }
        builder.debugLoggingEnabled = s.debugLogging
    }
}

/** A first-frame signal that never fires, so DEFERRED work can only start via the timeout. */
object NeverFirstFrameSignal : io.github.santimattius.android.startup.FirstFrameSignal {
    override suspend fun await() = kotlinx.coroutines.awaitCancellation()
}

/** Holder of the settings the process is currently running with. */
object SampleStartup {
    @Volatile
    var current: StartupSettings = StartupSettings()
        private set

    /** Applies [settings] through a single `configure` call and remembers them. */
    fun apply(settings: StartupSettings) {
        current = settings
        AppStartupInitializer.configure { settings.applyTo(this) }
    }

    /** Enables dispatcher instrumentation for [block], then restores the exact captured settings. */
    suspend fun <T> withDispatcherInstrumentation(block: suspend () -> T): T {
        val previous = current
        apply(previous.copy(dispatcherInstrumentation = true))
        return try {
            block()
        } finally {
            apply(previous)
        }
    }
}

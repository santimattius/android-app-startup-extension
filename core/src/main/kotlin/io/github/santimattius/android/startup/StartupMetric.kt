package io.github.santimattius.android.startup

/**
 * Timing and outcome data for a single initializer execution.
 *
 * @property initializerName [Class.simpleName] of the initializer.
 * @property durationMs Wall-clock milliseconds elapsed during [create()][io.github.santimattius.android.startup.initializer.StartupSyncInitializer.create].
 * @property isAsync `true` for [io.github.santimattius.android.startup.initializer.StartupAsyncInitializer], `false` for sync.
 * @property success `true` if [create()] completed without throwing, `false` otherwise.
 */
data class StartupMetric(
    val initializerName: String,
    val durationMs: Long,
    val isAsync: Boolean,
    val success: Boolean,
)

/**
 * Callback invoked once per initializer execution, whether it succeeds or fails.
 *
 * Register a listener via [AppStartupInitializer.metricsListener] before startup begins.
 */
fun interface StartupMetricsListener {
    fun onInitializerCompleted(metric: StartupMetric)
}

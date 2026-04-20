package io.github.santimattius.android.startup

/**
 * Timing and outcome data for a single initializer execution.
 *
 * Emitted by [AppStartupInitializer.metricsFlow] after each initializer completes.
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

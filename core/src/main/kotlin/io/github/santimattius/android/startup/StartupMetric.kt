package io.github.santimattius.android.startup

/**
 * Timing and outcome data for a single initializer execution.
 *
 * Emitted by [AppStartupInitializer.metricsFlow] after each initializer completes.
 *
 * @property initializerName [Class.simpleName] of the initializer.
 * @property durationMs Monotonic milliseconds elapsed during [create()][io.github.santimattius.android.startup.initializer.StartupSyncInitializer.create].
 * @property isAsync `true` for [io.github.santimattius.android.startup.initializer.StartupAsyncInitializer], `false` for sync.
 * @property success `true` if [create()] completed without throwing, `false` otherwise.
 * @property wasCancelled `true` if the initializer was interrupted by structured cancellation rather
 *   than a real failure. When `true`, [success] will be `false` — but the two cases are semantically
 *   distinct: a cancellation is not an error and must not be reported as one to alerting systems.
 */
data class StartupMetric(
    val initializerName: String,
    val durationMs: Long,
    val isAsync: Boolean,
    val success: Boolean,
    val wasCancelled: Boolean = false,
)

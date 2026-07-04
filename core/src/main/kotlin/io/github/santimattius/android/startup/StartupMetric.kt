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
 * @property dispatcherName Name of the [kotlinx.coroutines.CoroutineDispatcher] the block actually ran
 *   on. Empty for sync initializers (main-thread only, no dispatcher involved).
 * @property threadName Name of the thread the block actually ran on. Empty for sync initializers.
 * @property concurrentActiveCount Snapshot of how many async initializers were actively executing
 *   `create()` at the moment this one was launched. Always `0` for sync initializers.
 * @property queueDelayMs Monotonic milliseconds elapsed between the initializer becoming ready to run
 *   and actually starting execution (semaphore wait + dispatcher scheduling latency). Always `0` for
 *   sync initializers.
 */
data class StartupMetric(
    val initializerName: String,
    val durationMs: Long,
    val isAsync: Boolean,
    val success: Boolean,
    val wasCancelled: Boolean = false,
    val dispatcherName: String = "",
    val threadName: String = "",
    val concurrentActiveCount: Int = 0,
    val queueDelayMs: Long = 0L,
)

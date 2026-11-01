package io.github.santimattius.android.startup.dispatcher

/**
 * Point-in-time counters of one instrumented dispatcher (see
 * [io.github.santimattius.android.startup.AppStartupConfig.Builder.dispatcherInstrumentationEnabled]).
 *
 * @property label Stable key of the dispatcher (the wrapped dispatcher's `toString()` at wrap time;
 *   disambiguated with a `#n` suffix when two distinct dispatchers share the same text).
 * @property peakConcurrency Highest number of dispatched blocks running at the same time.
 * @property maxQueueDelayMs Longest time (ms) a block waited between dispatch and starting to run.
 *   This is dispatcher/pool latency only; library semaphore wait is
 *   [io.github.santimattius.android.startup.StartupMetric.permitWaitMs].
 * @property totalDispatched Number of blocks dispatched through the dispatcher.
 */
data class DispatcherSnapshot(
    val label: String,
    val peakConcurrency: Int,
    val maxQueueDelayMs: Long,
    val totalDispatched: Long,
)

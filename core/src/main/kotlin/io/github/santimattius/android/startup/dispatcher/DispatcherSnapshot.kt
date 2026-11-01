package io.github.santimattius.android.startup.dispatcher

/**
 * Point-in-time counters of one instrumented dispatcher (see
 * [io.github.santimattius.android.startup.AppStartupConfig.Builder.dispatcherInstrumentationEnabled]).
 *
 * @property label Key of the dispatcher, derived from the wrapped dispatcher's `toString()` at wrap
 *   time with the volatile parts removed (identity hash such as `@1a2b3c`, bracketed pool statistics
 *   such as `[Running, pool size = 2, ...]` and the package prefix), so it is stable across runs, e.g.
 *   `ThreadPoolExecutor` or `Dispatchers.IO`. Distinct dispatchers deriving the same text are
 *   disambiguated with a `#n` suffix. It cannot be set by the caller.
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

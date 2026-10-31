package io.github.santimattius.android.startup

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hot, bounded metrics stream.
 *
 * Late-collector contract: a collector that subscribes after startup receives the most recent
 * [capacity] metrics, in emission order. While the number of emitted metrics is `<= capacity`
 * (every initializer of a normal startup) that is the full startup; beyond it the OLDEST metrics are
 * dropped first, [publish] never suspends, and a single warning is logged on the first drop.
 */
internal class StartupMetricsBus(
    private val capacity: Int = DEFAULT_METRICS_REPLAY_CAPACITY,
) {

    private val _flow = MutableSharedFlow<StartupMetric>(
        replay = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val published = AtomicInteger(0)

    val metricsFlow: SharedFlow<StartupMetric> = _flow.asSharedFlow()

    fun publish(metric: StartupMetric) {
        _flow.tryEmit(metric)
        if (published.incrementAndGet() == capacity + 1) {
            StartupExtensionLogger.warning(
                "Metrics replay is bounded to $capacity; the oldest metrics are now dropped for late " +
                    "collectors. Raise AppStartupConfig.metricsReplayCapacity to keep more."
            )
        }
    }
}

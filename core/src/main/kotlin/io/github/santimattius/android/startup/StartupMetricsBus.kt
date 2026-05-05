package io.github.santimattius.android.startup

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class StartupMetricsBus {

    private val _flow = MutableSharedFlow<StartupMetric>(replay = Int.MAX_VALUE)

    val metricsFlow: SharedFlow<StartupMetric> = _flow.asSharedFlow()

    fun publish(metric: StartupMetric) {
        _flow.tryEmit(metric)
    }
}

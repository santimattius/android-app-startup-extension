package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.StartupMetric

/** One runnable use case: [run] exercises the library and returns the lines to display. */
class DemoCase(
    val id: String,
    val title: String,
    val description: String,
    val run: suspend (AppStartupInitializer) -> List<String>,
)

/** Most recent metric emitted for [cls] (the bounded replay cache holds the latest ones). */
fun AppStartupInitializer.lastMetricOf(cls: Class<*>): StartupMetric? =
    metricsFlow.replayCache.lastOrNull { it.initializerFqn == cls.name }

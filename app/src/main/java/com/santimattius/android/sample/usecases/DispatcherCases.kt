package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.AppStartupInitializer

/** Use case D: dispatcher instrumentation (opt-in, debug/measurement only). */
object DispatcherCases {
    private fun snapshotLines(startup: AppStartupInitializer): List<String> =
        startup
            .dispatcherSnapshots()
            .values
            .map {
                "${it.label}: peak=${it.peakConcurrency} maxQueueDelay=${it.maxQueueDelayMs} ms " +
                    "dispatched=${it.totalDispatched}"
            }.ifEmpty { listOf("no snapshots (instrumentation is off)") }

    val all: List<DemoCase> =
        listOf(
            DemoCase(
                "instrumented-burst",
                "Run an instrumented dispatcher burst",
                "Temporarily enables dispatcher instrumentation, runs transient async initializers on " +
                    "IO, a single thread and Default, reads dispatcherSnapshots(), then automatically " +
                    "restores the exact previous settings. Debug/measurement only: wrapping " +
                    "Dispatchers.IO reduces its thread-pool elasticity and adds one dispatch hop.",
            ) { startup ->
                val lines =
                    SampleStartup.withDispatcherInstrumentation {
                        repeat(3) {
                            listOf(
                                IoDispatcherDemo::class.java,
                                SingleThreadDispatcherDemo::class.java,
                                TransientAsyncDemo::class.java,
                            ).forEach { startup.doInitialize<Any>(it) }
                        }
                        snapshotLines(startup)
                    }
                lines + "previous settings restored"
            },
        )
}

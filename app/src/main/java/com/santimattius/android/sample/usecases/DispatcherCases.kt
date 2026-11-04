package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.AppStartupInitializer

/** Use case D: dispatcher instrumentation (opt-in, debug/measurement only). */
object DispatcherCases {

    private fun snapshotLines(startup: AppStartupInitializer): List<String> =
        startup.dispatcherSnapshots().values.map {
            "${it.label}: peak=${it.peakConcurrency} maxQueueDelay=${it.maxQueueDelayMs} ms " +
                "dispatched=${it.totalDispatched}"
        }.ifEmpty { listOf("no snapshots (instrumentation is off)") }

    val all: List<DemoCase> = listOf(
        DemoCase(
            "enable", "1. Enable dispatcherInstrumentationEnabled",
            "Calls configure{} once with every option of the current settings plus " +
                "dispatcherInstrumentationEnabled = true. Caveat: debug/measurement only. Wrapping " +
                "Dispatchers.IO reduces its thread-pool elasticity and adds one dispatch hop.",
        ) { _ ->
            SampleStartup.apply(SampleStartup.current.copy(dispatcherInstrumentation = true))
            listOf("configure() applied, dispatcherInstrumentation = true")
        },
        DemoCase(
            "burst", "2. Run async initializers and read dispatcherSnapshots()",
            "Runs transient async initializers on IO, a single thread and the default dispatcher. " +
                "On-demand calls are serialized by the library, so peak stays at 1; the eager startup " +
                "path is where concurrency shows (see the debug scenarios).",
        ) { startup ->
            repeat(3) {
                listOf(IoDispatcherDemo::class.java, SingleThreadDispatcherDemo::class.java, TransientAsyncDemo::class.java)
                    .forEach { startup.doInitialize<Any>(it) }
            }
            snapshotLines(startup)
        },
        DemoCase(
            "restore", "3. Restore the previous settings",
            "Calls configure{} again with instrumentation switched off.",
        ) { _ ->
            SampleStartup.apply(SampleStartup.current.copy(dispatcherInstrumentation = false))
            listOf("instrumentation off: ${!SampleStartup.current.dispatcherInstrumentation}")
        },
    )
}

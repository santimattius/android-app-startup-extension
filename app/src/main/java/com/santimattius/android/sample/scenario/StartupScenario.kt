package com.santimattius.android.sample.scenario

import com.santimattius.android.sample.usecases.StartupSettings

/**
 * Debug-only startup scenarios. Each maps to one [StartupSettings] applied in
 * `Application.attachBaseContext`, i.e. before the library's provider runs.
 */
enum class StartupScenario(
    val title: String,
    val description: String,
    val observe: String,
    val settings: StartupSettings,
) {
    Default(
        "Default", "No configure{} call: library defaults, as in release.",
        "Debug demo initializers all start together; permitWait is 0 everywhere.",
        StartupSettings(),
    ),
    CapOneIo(
        "Concurrency cap = 1 on Dispatchers.IO",
        "maxConcurrentAsyncInitializers = 1 and defaultAsyncDispatcher = Dispatchers.IO.",
        "Async metrics show dispatcher Dispatchers.IO and growing permitWait (they run one at a time).",
        StartupSettings(maxConcurrentAsyncInitializers = 1, useIoAsDefaultDispatcher = true),
    ),
    TopologicalValidated(
        "Topological sync + Validated async",
        "SyncOrderingStrategy.Topological and AsyncInitializerStrategy.Validated.",
        "Same metrics as Default for acyclic graphs; the strategies only differ on cycles " +
            "(reported upfront) and in how many coroutines are launched. Not visible in the metrics.",
        StartupSettings(topologicalSync = true, validatedAsync = true),
    ),
    DeferredFirstFrame(
        "DEFERRED gated on the first frame",
        "Default first-frame signal and deferredStartupTimeoutMs.",
        "DebugDeferredInitializer has priority DEFERRED: its start offset is later than the " +
            "NORMAL ones, after the first frame is drawn.",
        StartupSettings(deferredStartupTimeoutMs = 5_000),
    ),
    DeferredTimeout(
        "DEFERRED released by the timeout",
        "A first-frame signal that never fires and deferredStartupTimeoutMs = 3000.",
        "DebugDeferredInitializer starts about 3 s after process start (processStartOffset), " +
            "proving the timeout fallback.",
        StartupSettings(neverSignalFirstFrame = true, deferredStartupTimeoutMs = 3_000),
    ),
    Instrumented(
        "Dispatcher instrumentation at startup",
        "dispatcherInstrumentationEnabled = true from the start.",
        "dispatcherSnapshots() is non-empty with peakConcurrency above 1 for startup initializers.",
        StartupSettings(dispatcherInstrumentation = true),
    ),
    StrictMode(
        "Strict mode with threshold 1",
        "strictModeCheckEnabled = true, strictModeConcurrencyThreshold = 1 (and debug logging, " +
            "which the warning requires).",
        "logcat tag StartupLogger shows 'Async startup concurrency reached N ...' once.",
        StartupSettings(strictModeCheck = true, strictModeConcurrencyThreshold = 1, debugLogging = true),
    ),
    ReplayTwo(
        "Metrics replay capacity = 2",
        "metricsReplayCapacity = 2 (read when the library singleton is created).",
        "A late collector receives only the last 2 metrics instead of all of them.",
        StartupSettings(metricsReplayCapacity = 2),
    ),
    DebugLogging(
        "Debug logging", "debugLoggingEnabled = true.",
        "logcat tag StartupLogger prints Discovered / Initializing / Initialized lines.",
        StartupSettings(debugLogging = true),
    );

    companion object {
        fun fromName(name: String?): StartupScenario = entries.firstOrNull { it.name == name } ?: Default
    }
}

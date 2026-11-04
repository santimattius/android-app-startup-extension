package com.santimattius.android.sample.ui

/**
 * State-based navigation: every screen is one enum entry, [parent] drives system Back.
 * Only [Home] is composed at startup; the others are composed lazily after a tap, which keeps
 * the first screen free of any [io.github.santimattius.android.startup.AppStartupInitializer] access.
 */
enum class Route(val title: String, val summary: String, val parent: Route?) {
    Home("App Startup Sample", "", null),
    Hub("Use cases", "", Home),
    Observability(
        "Live observability",
        "startupState, metricsFlow table, isAllStartedJobsDone and bounded replay to a late collector.",
        Hub,
    ),
    OnDemand(
        "On-demand initialization",
        "doInitialize, initializeComponent, retained vs transient, eager flags and dependencies.",
        Hub,
    ),
    Waiting(
        "Waiting for startup",
        "awaitAllStartJobs() vs awaitAllStartJobs(timeoutMs) and onAppStartupLaunched.",
        Hub,
    ),
    Dispatchers(
        "Dispatcher instrumentation",
        "Enable dispatcherInstrumentationEnabled and read dispatcherSnapshots().",
        Hub,
    );

    companion object {
        /** Entries listed by the hub, in display order. */
        val useCases: List<Route> = entries.filter { it.parent == Hub }

        fun fromName(name: String?): Route = entries.firstOrNull { it.name == name } ?: Home
    }
}

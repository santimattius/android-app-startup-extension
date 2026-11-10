package io.github.santimattius.android.startup

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_META_DATA
import android.os.Bundle
import android.os.Trace
import io.github.santimattius.android.startup.dispatcher.DispatcherInstrumentation
import io.github.santimattius.android.startup.dispatcher.DispatcherSnapshot
import io.github.santimattius.android.startup.engine.AppStartupCoroutinesEngine
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.github.santimattius.android.startup.trace.StartupTraceNames
import io.github.santimattius.android.startup.trace.StartupTracer
import io.github.santimattius.android.startup.trace.SystemStartupTracer
import io.github.santimattius.android.startup.trace.asyncSection
import io.github.santimattius.android.startup.trace.section
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

/**
 * The central class for managing the initialization of application components during startup.
 *
 * This class handles the discovery and initialization of both synchronous and asynchronous
 * startup components. It uses a combination of metadata from the `InitializationProvider`
 * and dependency information provided by the `StartupSyncInitializer` and `StartupAsyncInitializer`
 * implementations to ensure that components are initialized in the correct order.
 *
 * The initializer supports cycle detection, ensuring that circular dependencies between
 * components do not cause infinite loops. It also provides debugging information via
 * `StartupExtensionLogger` when the DEBUG flag is enabled.
 *
 * @property context The application context.
 * @property coroutineDispatcher The `CoroutineDispatcher` to use for asynchronous initializations.
 *   If `null`, the default dispatcher from [AppStartupCoroutinesEngine] will be used.
 *
 * @constructor Creates an `AppStartupInitializer` instance.
 *
 * @see StartupSyncInitializer
 * @see StartupAsyncInitializer
 * @see InitializationProvider
 * @see AppStartupCoroutinesEngine
 */
class AppStartupInitializer internal constructor(
    context: Context,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val tracer: StartupTracer = SystemStartupTracer,
) {
    private val registry = InitializerRegistry(tracer)

    internal val coroutinesEngine =
        AppStartupCoroutinesEngine(
        coroutineDispatcher ?: Dispatchers.Default,
        cap = config.maxConcurrentAsyncInitializers,
        tracer = tracer,
    )

    private val metricsBus = StartupMetricsBus(config.metricsReplayCapacity, tracer)

    private val dispatcherInstrumentation = DispatcherInstrumentation()

    /**
     * Per-dispatcher counters keyed by label. Empty unless
     * [AppStartupConfig.Builder.dispatcherInstrumentationEnabled] is `true` (debug/measurement only).
     */
    fun dispatcherSnapshots(): Map<String, DispatcherSnapshot> = dispatcherInstrumentation.snapshots()

    /**
     * Hot [SharedFlow] that emits a [StartupMetric] after each initializer completes,
     * whether it succeeds or throws.
     *
     * Late collectors get a bounded replay: a collector joining after startup finishes receives the
     * most recent [AppStartupConfig.metricsReplayCapacity] (default 256) metrics in emission order,
     * i.e. the full startup unless more initializers than that ran. Beyond the bound the oldest
     * metrics are dropped first and emitters never suspend.
     *
     * **Slow live collectors lose metrics too:** the same bound applies to a collector that is already
     * subscribed. There is no extra buffer, so if it lags behind by more than the capacity it skips
     * the oldest metrics (`DROP_OLDEST`). The drop warning is only logged when debug logging is
     * enabled, so the loss is otherwise silent.
     *
     * **Thread of emission:** metrics are emitted from the initializer's own execution context —
     * the caller thread for sync initializers, and the resolved `dispatcher()` context for async ones.
     * [SharedFlow] handles the cross-thread delivery internally: your `collect` lambda always
     * runs on the dispatcher of the coroutine that launched the collection, so no manual
     * thread-switching is required on the consumer side.
     *
     * Collect from a lifecycle-aware scope to avoid retaining UI references in the singleton:
     * ```kotlin
     * lifecycleScope.launch {
     *     AppStartupInitializer.getInstance(context).metricsFlow.collect { metric ->
     *         updateDashboard(metric) // runs on Main — safe to touch UI here
     *     }
     * }
     * ```
     */
    val metricsFlow: SharedFlow<StartupMetric> get() = metricsBus.metricsFlow

    private val initialized = ConcurrentHashMap<Class<*>, Any>()
    private val syncDiscovered: MutableSet<Class<out StartupSyncInitializer<*>>> = CopyOnWriteArraySet()
    private val asyncDiscovered: MutableSet<Class<out StartupAsyncInitializer<*>>> = CopyOnWriteArraySet()

    private val applicationContext: Context = context.applicationContext

    private val syncLocks = SyncLockCoordinator()
    private val mutex = Mutex()

    private val planner = StartupPlanner(registry, tracer)

    private val syncRunner =
        SyncRunner(
            tracer = tracer,
            registry = registry,
            initialized = initialized,
            syncDiscovered = syncDiscovered,
            applicationContext = applicationContext,
            syncLocks = syncLocks,
            metricsBus = metricsBus,
            planner = planner,
            config = { config },
        )

    private val asyncRunner =
        AsyncRunner(
            tracer = tracer,
            registry = registry,
            initialized = initialized,
            asyncDiscovered = asyncDiscovered,
            applicationContext = applicationContext,
            metricsBus = metricsBus,
            planner = planner,
            coroutinesEngine = coroutinesEngine,
            dispatcherInstrumentation = dispatcherInstrumentation,
            syncRunner = syncRunner,
            config = { config },
        )

    /**
     * Initializes a component of type [T] using its corresponding [StartupSyncInitializer].
     *
     * This function takes a class representing a [StartupSyncInitializer] and performs the initialization
     * process synchronously. It leverages the `doInitialize` function to handle the actual initialization logic.
     *
     * @param component The class representing the [StartupSyncInitializer] responsible for initializing the desired component.
     *                  This class must implement the [StartupSyncInitializer] interface.
     * @param T The type of the component being initialized.
     *          It is constrained to be a non-nullable type (`Any`).
     * @return The initialized component of type [T].
     * @throws Exception if any error happens during the component initialization.
     *
     * @see StartupSyncInitializer
     * @see doInitialize
     */
    fun <T : Any> initializeComponent(component: Class<out StartupSyncInitializer<T>>): T = doInitialize(component)

    /**
     * Checks if a given [StartupSyncInitializer] component has been eagerly initialized during startup.
     *
     * Eager initialization means that the component's [StartupSyncInitializer.initialize] method
     * was called during the application's startup phase, before any explicit request to sync data.
     * This is typically done for components that are critical for the application's initial state.
     *
     * This function determines whether a specific [StartupSyncInitializer] has been marked as
     * eagerly initialized by checking if it's present in the internal `syncDiscovered` set.
     *
     * @param component The class of the [StartupSyncInitializer] component to check.
     * @return `true` if the component has been eagerly initialized; `false` otherwise.
     * @see StartupSyncInitializer
     */
    fun isEagerlySyncInitialized(component: Class<out StartupSyncInitializer<*>>): Boolean = syncDiscovered.contains(component)

    /**
     * Checks if a given [StartupAsyncInitializer] component is eagerly initialized asynchronously.
     *
     * Eagerly initialized asynchronous components are those that are discovered and their
     * initialization is started immediately during application startup, rather than being
     * deferred until they are needed.
     *
     * @param component The class representing the [StartupAsyncInitializer] component to check.
     * @return `true` if the component is eagerly initialized asynchronously, `false` otherwise.
     *
     * @see StartupAsyncInitializer
     */
    fun isEagerlyAsyncInitialized(component: Class<out StartupAsyncInitializer<*>>): Boolean = asyncDiscovered.contains(component)

    /**
     * Initializes a StartupSyncInitializer component if it hasn't been initialized yet.
     *
     * This function coordinates each component independently. The coordinator is held only while
     * claiming, waiting for, or releasing component ownership; `create()` and dependency work run
     * outside it.
     *
     * @param component The Class object representing the StartupSyncInitializer component to initialize.
     *                  This must be a class that extends StartupSyncInitializer.
     * @param T The expected type of the initialized component. This is used for type casting the returned value.
     *          The actual initialized type is determined by the `component` parameter.
     * @return The initialized instance of the `StartupSyncInitializer` component, cast to type `T`.
     * @throws ClassCastException if the initialized object cannot be cast to type `T`.
     * @throws IllegalStateException if the initialization process encounters any issue.
     */
    fun <T> doInitialize(component: Class<out StartupSyncInitializer<*>>): T =
        tracer.section({ StartupTraceNames.of("onDemand", component.name) }) {
            syncRunner.initializeUnderSyncLock<T>(component, HashSet())
        }

    /**
     * Initializes a component of type [StartupAsyncInitializer] asynchronously and ensures it's only initialized once.
     *
     * This function utilizes a mutex to provide thread safety during the initialization process. It checks if the
     * specified component has already been initialized. If it has, it returns the previously initialized instance.
     * Otherwise, it triggers the asynchronous initialization using [doAsyncInitialize], stores the result, and returns it.
     *
     * @param component The class representing the component to be initialized. Must be a subclass of [StartupAsyncInitializer].
     * @return The initialized instance of the component, cast to the appropriate type [T].
     * @throws Exception Any exception thrown during the initialization process within `doAsyncInitialize`.
     * @throws ClassCastException If the initialized component is not of type [T]
     *
     * @param T The expected type of the initialized component.
     *
     * @see StartupAsyncInitializer
     * @see doAsyncInitialize
     * @see Mutex
     */
    suspend fun <T> doInitialize(component: Class<out StartupAsyncInitializer<*>>): T {
        // Fast path: already initialized, no lock needed (ConcurrentHashMap read is safe)
        initialized[component]?.let { return it as T }
        return tracer.asyncSection({ StartupTraceNames.of("onDemand", component.name) }) {
            tracer.asyncSection({ StartupTraceNames.of("lockWait::async", component.name) }) { mutex.lock() }
            try {
                initialized[component] ?: asyncRunner.doAsyncInitialize<Any>(component, HashSet(), startupGraph = false)
            } finally {
                mutex.unlock()
            }
        } as T
    }

    /**
     * Discovers and initializes components based on the metadata provided by the given [initializationProvider].
     *
     * This function uses the provided [initializationProvider] class to locate the corresponding
     * `ContentProvider` within the application's manifest. It then extracts the associated metadata
     * and performs synchronous and asynchronous initialization operations using that metadata.
     *
     * The function wraps the discovery and initialization process within a [Trace] section for performance analysis.
     * It also handles potential `PackageManager.NameNotFoundException` errors that might occur if the
     * provider is not found and throws a [StartupExtensionException] in that case.
     *
     * @param initializationProvider The class of the [InitializationProvider] to use for discovery.
     *                                This class should correspond to a `ContentProvider` declared in the
     *                                application's manifest, which contains the relevant metadata.
     * @throws StartupExtensionException If the provided [initializationProvider] is not found in the
     *                                   application's manifest or if there are issues retrieving provider information.
     */
    internal fun discoverAndInitialize(initializationProvider: Class<out InitializationProvider>) {
        try {
            Trace.beginSection(SECTION_NAME)
            val provider = ComponentName(applicationContext, initializationProvider)
            val providerInfo =
                Trace.beginSection(TRACE_PROVIDER_INFO).let {
                try {
                    applicationContext.packageManager.getProviderInfo(provider, GET_META_DATA)
                } finally {
                    Trace.endSection()
                }
            }
            // A provider with no <meta-data> (no initializers declared) has null metaData.
            val metadata = providerInfo.metaData ?: Bundle.EMPTY
            syncRunner.syncDiscoverAndInitialize(metadata)
            asyncRunner.asyncDiscoverAndInitialize(metadata)
        } catch (exception: PackageManager.NameNotFoundException) {
            throw StartupExtensionException(exception)
        } finally {
            Trace.endSection()
        }
    }

    /** Delegates to [StartupPlanner.topologicalSort]. */
    internal fun topologicalSort(discovered: Set<Class<out StartupSyncInitializer<*>>>): List<Class<out StartupSyncInitializer<*>>> =
        planner.topologicalSort(discovered)


    /** Delegates to [StartupPlanner.asyncValidateAndGetRoots] / [StartupPlanner.computeEffectivePriorities]. */
    internal fun asyncValidateAndGetRoots(
        discovered: Set<Class<out StartupAsyncInitializer<*>>>,
        includeTransitiveDependencies: Boolean = false,
    ): Set<Class<out StartupAsyncInitializer<*>>> = planner.asyncValidateAndGetRoots(discovered, includeTransitiveDependencies)

    internal fun computeEffectivePriorities(
        discovered: Set<Class<out StartupAsyncInitializer<*>>>,
    ): Map<Class<out StartupAsyncInitializer<*>>, StartupPriority> = planner.computeEffectivePriorities(discovered)


    companion object {
        private const val STARTUP_LOG_TAG = "StartupLogger"
        private const val SECTION_NAME = "StartupExtension"
        private const val TRACE_PROVIDER_INFO = "Startup::getProviderInfo"

        @Volatile
        private var config: AppStartupConfig = AppStartupConfig.default()

        /**
         * Applies a configuration block to the library.
         *
         * Call before [InitializationProvider] runs — the earliest safe point is
         * [android.app.Application.attachBaseContext]:
         *
         * ```kotlin
         * override fun attachBaseContext(base: Context) {
         *     super.attachBaseContext(base)
         *     AppStartupInitializer.configure {
         *         debugLoggingEnabled    = BuildConfig.DEBUG
         *         strictModeCheckEnabled = BuildConfig.DEBUG
         *         syncOrderingStrategy   = SyncOrderingStrategy.Topological
         *     }
         * }
         * ```
         */
        fun configure(block: AppStartupConfig.Builder.() -> Unit) {
            val newConfig = AppStartupConfig.Builder().apply(block).build()
            config = newConfig
            StartupExtensionLogger.enable(newConfig.debugLoggingEnabled)
        }

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var sInstance: AppStartupInitializer? = null

        private val sLock = Any()

        /**
         * Provides a singleton instance of [AppStartupInitializer].
         *
         * This function implements a double-checked locking pattern to ensure that only one instance
         * of [AppStartupInitializer] is created and shared across the application. It's thread-safe
         * and efficiently manages the initialization of the singleton.
         *
         * @param context The application context required for initializing [AppStartupInitializer].
         *                This should be the application context to avoid memory leaks.
         * @return The singleton instance of [AppStartupInitializer]. It is guaranteed to be non-null.
         */
        fun getInstance(context: Context): AppStartupInitializer {
            if (sInstance == null) {
                synchronized(sLock) {
                    if (sInstance == null) {
                        sInstance = AppStartupInitializer(context)
                    }
                }
            }
            return sInstance!!
        }
    }
}
package io.github.santimattius.android.startup

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_META_DATA
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
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
import io.github.santimattius.android.startup.trace.closeAsyncSection
import io.github.santimattius.android.startup.trace.closeSection
import io.github.santimattius.android.startup.trace.mark
import io.github.santimattius.android.startup.trace.openAsyncSection
import io.github.santimattius.android.startup.trace.openSection
import io.github.santimattius.android.startup.trace.section
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    private data class AsyncInFlightKey(
        val component: Class<out StartupAsyncInitializer<*>>,
        val startupGraph: Boolean,
    )

    private val initialized = ConcurrentHashMap<Class<*>, Any>()
    private val asyncInFlight = ConcurrentHashMap<AsyncInFlightKey, CompletableDeferred<Any>>()
    private val transientAsyncStartupComponents = ConcurrentHashMap<Class<out StartupAsyncInitializer<*>>, Any>()
    private val pendingAsyncStartupRoots = AtomicInteger(0)
    private val syncDiscovered: MutableSet<Class<out StartupSyncInitializer<*>>> = CopyOnWriteArraySet()
    private val asyncDiscovered: MutableSet<Class<out StartupAsyncInitializer<*>>> = CopyOnWriteArraySet()

    private val applicationContext: Context = context.applicationContext

    private class SyncLockEntry {
        var owner: Thread? = null
        var depth = 0
        var references = 0
    }

    private val syncCoordinator = java.lang.Object()
    private val syncLockEntries = mutableMapOf<Class<out StartupSyncInitializer<*>>, SyncLockEntry>()
    private val syncWaits = mutableMapOf<Thread, Class<out StartupSyncInitializer<*>>>()
    private val mutex = Mutex()

    /**
     * Guards the strict-mode concurrency warning so it is emitted AT MOST ONCE per startup
     * (per initializer instance), regardless of how many initializers breach the threshold.
     */
    private val strictModeConcurrencyWarned = AtomicBoolean(false)

    private val planner = StartupPlanner(registry, tracer)

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
            initializeUnderSyncLock<T>(component, HashSet())
        }

    private fun <T> initializeUnderSyncLock(
        component: Class<out StartupSyncInitializer<*>>,
        initializing: MutableSet<Class<*>>,
    ): T {
        val waiting = tracer.openSection { StartupTraceNames.of("lockWait::sync", null) }
        var acquired = false
        try {
            acquireSyncLock(component)
            acquired = true
            tracer.closeSection(waiting)
            return (initialized[component] ?: doInitialize<T>(component, initializing)) as T
        } finally {
            if (!acquired) tracer.closeSection(waiting)
            if (acquired) releaseSyncLock(component)
        }
    }

    private fun acquireSyncLock(component: Class<out StartupSyncInitializer<*>>) {
        val waiter = Thread.currentThread()
        synchronized(syncCoordinator) {
            val entry = syncLockEntries.getOrPut(component) { SyncLockEntry() }
            entry.references++
            try {
                while (true) {
                    val owner = entry.owner
                    when {
                        owner == null -> {
                            entry.owner = waiter
                            entry.depth = 1
                            return
                        }

                        owner === waiter -> {
                            entry.depth++
                            return
                        }

                        else -> {
                            syncWaits[waiter] = component
                            if (createsSyncWaitCycle(waiter, owner)) {
                                syncWaits.remove(waiter)
                                throw StartupExtensionException(
                                    "Cannot initialize ${component.name}. Cross-thread cycle detected.",
                                )
                            }
                            try {
                                syncCoordinator.wait()
                            } catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw IllegalStateException("Interrupted while waiting for synchronous initialization.", interrupted)
                            } finally {
                                syncWaits.remove(waiter)
                            }
                        }
                    }
                }
            } catch (throwable: Throwable) {
                releaseSyncReference(component, entry)
                throw throwable
            }
        }
    }

    private fun createsSyncWaitCycle(
        waiter: Thread,
        owner: Thread,
    ): Boolean {
        val visited = HashSet<Thread>()
        var current: Thread? = owner
        while (current != null && visited.add(current)) {
            if (current === waiter) return true
            val waitingFor = syncWaits[current] ?: return false
            current = syncLockEntries[waitingFor]?.owner
        }
        return false
    }

    private fun releaseSyncLock(component: Class<out StartupSyncInitializer<*>>) {
        val owner = Thread.currentThread()
        synchronized(syncCoordinator) {
            val entry = checkNotNull(syncLockEntries[component]) { "Missing sync lock entry for ${component.name}" }
            check(entry.owner === owner) { "Thread does not own sync lock for ${component.name}" }
            entry.depth--
            if (entry.depth == 0) {
                entry.owner = null
                syncCoordinator.notifyAll()
            }
            releaseSyncReference(component, entry)
        }
    }

    private fun releaseSyncReference(
        component: Class<out StartupSyncInitializer<*>>,
        entry: SyncLockEntry,
    ) {
        check(entry.references > 0) { "Missing sync lock reference for ${component.name}" }
        entry.references--
        if (entry.references == 0) {
            check(entry.owner == null) { "Sync lock entry released while ${component.name} is owned" }
            syncLockEntries.remove(component)
        }
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
                initialized[component] ?: doAsyncInitialize(component, HashSet(), startupGraph = false)
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
            syncDiscoverAndInitialize(metadata)
            asyncDiscoverAndInitialize(metadata)
        } catch (exception: PackageManager.NameNotFoundException) {
            throw StartupExtensionException(exception)
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Initializes a given [StartupSyncInitializer] component and its dependencies.
     *
     * This function performs a depth-first initialization of [StartupSyncInitializer] components,
     * ensuring that each component's dependencies are initialized before the component itself.
     * It also handles cycle detection to prevent infinite loops.
     *
     * @param component The [Class] of the [StartupSyncInitializer] to initialize.
     *                  It must be a concrete class with a no-argument constructor.
     * @param initializing A mutable set used to track the components currently being initialized.
     *                     This set is used for cycle detection.
     * @return The result of calling the [StartupSyncInitializer.create] method on the initialized component.
     * @throws IllegalArgumentException If a cycle is detected in the dependency graph.
     * @throws StartupExtensionException If any exception occurs during initialization,
     *                                  it is wrapped in a [StartupExtensionException].
     * @throws NoSuchMethodException if the component class does not have a default constructor.
     * @throws InstantiationException if the component class is an abstract class or an interface.
     * @throws IllegalAccessException if the no-arg constructor of component is not accessible
     * @throws java.lang.reflect.InvocationTargetException if the no-arg constructor throws an exception
     * @throws ClassCastException if the instantiated class does not implement StartupSyncInitializer
     * @param T The type returned by the initializer
     *
     * @suppress
     */
    private fun <T> doInitialize(
        component: Class<out StartupSyncInitializer<*>>,
        initializing: MutableSet<Class<*>>,
    ): T {
        val nodeOpened = tracer.openSection { StartupTraceNames.of(null, component.name) }
        try {
            require(component !in initializing) { "Cannot initialize ${component.name}. Cycle detected." }

            var shouldRetain = true
            val result =
                initialized.getOrPut(component) {
                requireConcreteClass(component)
                initializing.add(component)
                try {
                    val initializer = registry.take(component) as StartupSyncInitializer<*>

                    shouldRetain = initializer.retainAfterStartup()

                    tracer.section({ StartupTraceNames.of("deps", component.name) }) {
                            initializer
                                .dependencies()
                            .filterNot { initialized.containsKey(it) }
                            .forEach { initializeUnderSyncLock<Any>(it, initializing) }
                    }

                    StartupExtensionLogger.info { "Initializing ${component.name}" }
                    val startNs = System.nanoTime()
                    val startUptimeMs = SystemClock.uptimeMillis()
                    var success = false
                    var failure: Throwable? = null
                    // Install a StrictMode policy that detects accidental I/O or disk
                    // access inside create(). Only active when the caller opts in via
                    // enableStrictModeCheck(true). The original policy is restored after
                    // every create() call regardless of outcome.
                        val savedPolicy =
                            if (config.strictModeCheckEnabled) {
                        StrictMode.getThreadPolicy().also {
                            StrictMode.setThreadPolicy(
                                        StrictMode.ThreadPolicy
                                            .Builder()
                                    .detectAll()
                                    .penaltyLog()
                                            .build(),
                            )
                        }
                            } else {
                                null
                            }
                    try {
                            val result =
                                tracer.section({ StartupTraceNames.of("create", component.name) }) {
                            initializer.create(applicationContext)
                        }
                        success = true
                        StartupExtensionLogger.info { "Initialized ${component.name}" }
                        result
                    } catch (t: Throwable) {
                        failure = t
                        throw t
                    } finally {
                        savedPolicy?.let { StrictMode.setThreadPolicy(it) }
                        val durationMs = (System.nanoTime() - startNs) / 1_000_000L
                        metricsBus.publish(
                            StartupMetric(
                                initializerName = component.simpleName,
                                durationMs = durationMs,
                                isAsync = false,
                                success = success,
                                startTimestampMs = startUptimeMs,
                                endTimestampMs = startUptimeMs + durationMs,
                                processStartOffsetMs = processStartOffsetMs(startUptimeMs),
                                initializerFqn = component.name,
                                errorType = failure?.javaClass?.name,
                                errorMessage = failure?.message,
                                ),
                        )
                    }
                } catch (throwable: Throwable) {
                    throw StartupExtensionException(throwable)
                } finally {
                    initializing.remove(component)
                }
            } as T
            if (!shouldRetain) initialized.remove(component)
            return result
        } finally {
            tracer.closeSection(nodeOpened)
        }
    }

    /**
     * Asynchronously initializes a given [StartupAsyncInitializer] component and its dependencies.
     *
     * This function recursively initializes a component and its dependencies in a depth-first manner.
     * It ensures that each component is initialized only once and detects circular dependencies.
     * It also uses tracing to profile the initialization process.
     *
     * @param T The type of the result returned by the initializer's `create()` method.
     * @param component The class of the [StartupAsyncInitializer] to initialize.
     * @param initializing A mutable set of component classes that are currently being initialized.
     *                     This is used to detect circular dependencies.
     * @return The result of the `create()` method of the initialized [StartupAsyncInitializer].
     * @throws IllegalStateException If a circular dependency is detected during initialization.
     * @throws StartupExtensionException If an error occurs during the initialization process.
     */
    private suspend fun <T> doAsyncInitialize(
        component: Class<out StartupAsyncInitializer<*>>,
        initializing: MutableSet<Class<*>>,
        startupGraph: Boolean,
    ): T {
        // Async slice: this node can suspend and resume on another thread, so a thread-bound
        // Trace.beginSection would corrupt the stack. The cookie is captured at begin.
        val nodeSpan = tracer.openAsyncSection { StartupTraceNames.of(null, component.name) }
        val completion = CompletableDeferred<Any>()
        var inFlightKey: AsyncInFlightKey? = null
        var shouldRetain = true
        try {
            require(component !in initializing) { "Cannot initialize ${component.name}. Cycle detected." }
            initialized[component]?.let { return it as T }
            if (startupGraph) {
                transientAsyncStartupComponents[component]?.let { return it as T }
            }

            requireConcreteClass(component)
            shouldRetain =
                (registry.peek(component) as StartupAsyncInitializer<*>).retainAfterStartup()
            val key = AsyncInFlightKey(component, startupGraph && !shouldRetain)
            inFlightKey = key
            val existingCompletion = asyncInFlight.putIfAbsent(key, completion)
            if (existingCompletion != null) return existingCompletion.await() as T

            val result = run {
                initializing.add(component)
                try {
                        val initializer =
                            registry
                                .take(component)
                        .also { it as StartupAsyncInitializer<*> }

                    val asyncInitializer = (initializer as StartupAsyncInitializer<*>)

                    tracer.asyncSection({ StartupTraceNames.of("deps", component.name) }) {
                            asyncInitializer
                                .dependencies()
                            .filterNot(initialized::containsKey)
                            .forEach { doAsyncInitialize<Any>(it, initializing, startupGraph) }

                        // Resolve cross-type dependencies: sync initializers that must complete
                        // before this async initializer's create() is invoked.
                            asyncInitializer
                                .syncDependencies()
                            .filterNot(initialized::containsKey)
                            .forEach { dependency ->
                                initializeUnderSyncLock<Any>(dependency, HashSet())
                            }
                    }

                    StartupExtensionLogger.info { "Initializing ${component.name}" }
                    val resolvedDispatcher =
                        asyncInitializer.dispatcher() ?: config.defaultAsyncDispatcher
                    val effectiveDispatcher: CoroutineDispatcher =
                        if (config.dispatcherInstrumentationEnabled) {
                            dispatcherInstrumentation.wrap(resolvedDispatcher)
                        } else {
                            resolvedDispatcher
                        }

                    // Dependencies are fully resolved above (deps + syncDependencies), so this clock
                    // starts AFTER dependency resolution and BEFORE the concurrency permit is
                    // acquired. queueDelayMs therefore measures semaphore backpressure + dispatcher
                    // scheduling latency only — the storm signal (ADR-5). The permit is acquired here,
                    // not around dependency resolution, so a parent never holds a permit while waiting
                    // on a child's permit — no deadlock even at cap=1 (ADR-2).
                    val requestedNs = System.nanoTime()
                        var permitAcquired = false
                        var permitWaitMs = 0L
                        var activeSnapshot = 0
                    var success = false
                    var wasCancelled = false
                    var failure: Throwable? = null
                    var startedNs = requestedNs
                    var bodyStarted = false
                    var startedUptimeMs = 0L
                    var executionThreadName = ""
                    try {
                            tracer.asyncSection({ StartupTraceNames.of("permitWait", component.name) }) {
                                coroutinesEngine.acquireAsyncPermit()
                            }
                            permitAcquired = true
                            permitWaitMs = (System.nanoTime() - requestedNs) / 1_000_000L

                            val result =
                                withContext(effectiveDispatcher) {
                            startedNs = System.nanoTime()
                            bodyStarted = true
                            startedUptimeMs = SystemClock.uptimeMillis()
                            executionThreadName = Thread.currentThread().name
                                    activeSnapshot = coroutinesEngine.enterActive()
                                    try {
                                        maybeWarnStrictModeConcurrency(activeSnapshot, effectiveDispatcher)
                            tracer.asyncSection({ StartupTraceNames.of("create", component.name) }) {
                                asyncInitializer.create(applicationContext)
                            }
                                    } finally {
                                        coroutinesEngine.exitActive()
                                    }
                        }
                        success = true
                        StartupExtensionLogger.info { "Initialized ${component.name}" }
                        result
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        wasCancelled = true
                        throw e
                    } catch (t: Throwable) {
                        failure = t
                        throw t
                    } finally {
                        val finishedNs = System.nanoTime()
                            if (!permitAcquired) {
                                permitWaitMs = (finishedNs - requestedNs) / 1_000_000L
                            }
                            if (permitAcquired) coroutinesEngine.releaseAsyncPermit()
                        val durationMs = (finishedNs - startedNs) / 1_000_000L
                            // A body that never started (cancelled while waiting for a permit or queued on a
                            // dispatcher) has no start instant, so queue delay runs up to cancellation.
                            // This keeps queueDelayMs >= permitWaitMs.
                        val queueDelayMs = ((if (bodyStarted) startedNs else finishedNs) - requestedNs) / 1_000_000L
                        metricsBus.publish(
                            StartupMetric(
                                initializerName = component.simpleName,
                                durationMs = durationMs,
                                isAsync = true,
                                success = success,
                                wasCancelled = wasCancelled,
                                dispatcherName = effectiveDispatcher.toString(),
                                threadName = executionThreadName,
                                concurrentActiveCount = activeSnapshot,
                                queueDelayMs = queueDelayMs,
                                startTimestampMs = startedUptimeMs,
                                // 0 (unknown) when the body never started, e.g. cancelled while queued.
                                endTimestampMs = if (startedUptimeMs == 0L) 0L else startedUptimeMs + durationMs,
                                processStartOffsetMs = processStartOffsetMs(startedUptimeMs),
                                priority = asyncInitializer.priority(),
                                initializerFqn = component.name,
                                errorType = failure?.javaClass?.name,
                                errorMessage = failure?.message,
                                permitWaitMs = permitWaitMs,
                                ),
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (throwable: Throwable) {
                    StartupExtensionLogger.error(
                        { "Error initializing ${component.name}: ${throwable.message}" },
                            throwable,
                    )
                    throw StartupExtensionException(throwable)
                } finally {
                    initializing.remove(component)
                }
            }
            if (shouldRetain) {
                initialized[component] = result
            } else if (startupGraph && pendingAsyncStartupRoots.get() > 0) {
                transientAsyncStartupComponents[component] = result
            }
            completion.complete(result)
            return result as T
        } catch (throwable: Throwable) {
            completion.completeExceptionally(throwable)
            throw throwable
        } finally {
            inFlightKey?.let { asyncInFlight.remove(it, completion) }
            tracer.closeAsyncSection(nodeSpan)
        }
    }

    /**
     * Discovers and initializes `StartupSyncInitializer` components.
     *
     * This function processes metadata from a `Bundle` to identify and initialize
     * classes that implement the `StartupSyncInitializer` interface. It filters the
     * bundle's keys based on whether their associated string value matches
     * `KEY_SYNC_INITIALIZER`. If a key matches, it attempts to load the
     * corresponding class, ensuring it is a subclass of `StartupSyncInitializer`.
     *
     * Discovered initializers are then initialized sequentially via `doInitialize`.
     *
     * @param metadata A `Bundle` containing metadata about potential `StartupSyncInitializer` classes.
     *                 Each key in the bundle represents a fully qualified class name, and the value
     *                 associated with the key indicates whether it should be considered for initialization.
     *                 If the value is equal to [KEY_SYNC_INITIALIZER] the class will be considered.
     *
     * @throws StartupExtensionException If a `ClassNotFoundException` occurs while loading a class.
     *
     * @see StartupSyncInitializer
     * @see KEY_SYNC_INITIALIZER
     * @see doInitialize
     */
    private fun syncDiscoverAndInitialize(metadata: Bundle) {
        tracer.section({ StartupTraceNames.of("discover::sync", null) }) {
            syncDiscovered.addAll(
                metadata
                    .keySet()
                    .filter { key -> metadata.getString(key) == KEY_SYNC_INITIALIZER }
                    .mapNotNull { key ->
                        try {
                            Class
                                .forName(key, false, applicationContext.classLoader)
                                .takeIf { StartupSyncInitializer::class.java.isAssignableFrom(it) }
                                ?.let { it as Class<out StartupSyncInitializer<*>> }
                                ?.also { StartupExtensionLogger.info { "Discovered $key" } }
                        } catch (e: ClassNotFoundException) {
                            throw StartupExtensionException(e)
                        }
                    },
            )
        }

        val ordered =
            when (config.syncOrderingStrategy) {
                is SyncOrderingStrategy.Lazy -> {
                    syncDiscovered.toList()
                }

                is SyncOrderingStrategy.Topological -> {
                tracer.section({ StartupTraceNames.of("resolveGraph::topological", null) }) {
                    topologicalSort(syncDiscovered)
                }
        }
            }

        val initializing = mutableSetOf<Class<*>>()
        ordered.forEach { initializeUnderSyncLock<Any>(it, initializing) }
    }

    /** Delegates to [StartupPlanner.topologicalSort]. */
    internal fun topologicalSort(discovered: Set<Class<out StartupSyncInitializer<*>>>): List<Class<out StartupSyncInitializer<*>>> =
        planner.topologicalSort(discovered)


    /**
     * Discovers and initializes asynchronous initializers based on metadata.
     *
     * This function processes a bundle of metadata to identify and load classes that implement
     * the [StartupAsyncInitializer] interface. It then launches coroutines to asynchronously
     * initialize these discovered initializers.
     *
     * @param metadata A [Bundle] containing metadata keys and values.
     *                 Keys are expected to be fully qualified class names.
     *                 Values are expected to be [KEY_ASYNC_INITIALIZER] if the key represents
     *                 an async initializer class.
     *
     * @throws StartupExtensionException If a class specified in the metadata cannot be found.
     *
     * **Process:**
     * 1. **Filtering:** Filters the metadata keys to find those associated with [KEY_ASYNC_INITIALIZER].
     * 2. **Class Loading:** For each filtered key, attempts to load the corresponding class using [Class.forName].
     * 3. **Type Checking:** Verifies if the loaded class implements the [StartupAsyncInitializer] interface.
     * 4. **Casting:** Safely casts the loaded class to `Class<out StartupAsyncInitializer<*>>` if it passes the type check.
     * 5. **Discovery Logging:** Logs a debug message if [StartupExtensionLogger.isDebugEnabled] is enabled, indicating which class was discovered.
     * 6. **Initialization Launch:** Launches a coroutine for each discovered initializer using [coroutinesEngine.launchStartJob].
     *    The coroutine executes the [doAsyncInitialize] function to perform the asynchronous initialization.
     * 7. **Concurrent Initialization handling**: uses the `initializing` set to avoid double initialize.
     *
     * **Error Handling:**
     * - If a class cannot be found, a [ClassNotFoundException] is caught and wrapped in a [StartupExtensionException], which is then thrown.
     *
     * **Side Effects:**
     * - Populates the `asyncDiscovered` list with the discovered [StartupAsyncInitializer] classes.
     * - Launches coroutines to asynchronously initialize the discovered classes.
     * - Logs debug messages to `StartupExtensionLogger` if debug mode is enabled.
     */
    private fun asyncDiscoverAndInitialize(metadata: Bundle) {
        tracer.section({ StartupTraceNames.of("discover::async", null) }) {
            asyncDiscovered.addAll(
                metadata
                    .keySet()
                    .filter { key -> metadata.getString(key) == KEY_ASYNC_INITIALIZER }
                    .mapNotNull { key ->
                        try {
                            Class
                                .forName(key, false, applicationContext.classLoader)
                                .takeIf { StartupAsyncInitializer::class.java.isAssignableFrom(it) }
                                ?.let { it as Class<out StartupAsyncInitializer<*>> }
                                ?.also { StartupExtensionLogger.info { "Discovered $key" } }
                        } catch (e: ClassNotFoundException) {
                            throw StartupExtensionException(e)
                        }
                    },
            )
        }
        val toLaunch =
            when (config.asyncInitializerStrategy) {
            is AsyncInitializerStrategy.Concurrent -> {
                tracer.section({ StartupTraceNames.of("resolveGraph::validateConcurrent", null) }) {
                    asyncValidateAndGetRoots(asyncDiscovered, includeTransitiveDependencies = true)
                }
                asyncDiscovered
            }

                is AsyncInitializerStrategy.Validated -> {
                tracer.section({ StartupTraceNames.of("resolveGraph::validateRoots", null) }) {
                    asyncValidateAndGetRoots(asyncDiscovered)
                }
        }
            }

        // Resolve priority inversions first (clamps a DEFERRED node up to its most-eager transitive
        // dependent and warns once), then partition the roots we are about to launch by EFFECTIVE
        // priority. Eager (NORMAL/CRITICAL — CRITICAL behaves like NORMAL in v1) roots launch
        // immediately on the critical path; DEFERRED roots are gated behind the first-frame signal.
        val effective =
            tracer.section({ StartupTraceNames.of("resolveGraph::effectivePriorities", null) }) {
            computeEffectivePriorities(asyncDiscovered)
        }
        val (deferredRoots, eagerRoots) =
            toLaunch.partition {
            effective[it] == StartupPriority.DEFERRED
        }
        val startupRootCount = eagerRoots.size + deferredRoots.size
        if (startupRootCount > 0) pendingAsyncStartupRoots.addAndGet(startupRootCount)

        eagerRoots.forEach { initializer ->
            // Each job gets its own initializing set: cycle detection is per-chain,
            // and sharing a mutable set across concurrent coroutines is a data race.
            coroutinesEngine.launchStartJob {
                doAsyncInitialize<Any>(initializer, HashSet(), startupGraph = true)
            }
        }
        coroutinesEngine.startJobs.takeLast(eagerRoots.size).forEach { job ->
            job.invokeOnCompletion { completeAsyncStartupRoot() }
        }

        if (deferredRoots.isNotEmpty()) {
            // Resolve the effective signal ONCE so every deferred root shares a single frame
            // signal / lifecycle registration. Resolved lazily (only when something is actually
            // deferred) so the static config never needs to hold a Context.
            val signal = config.firstFrameSignal ?: AndroidFirstFrameSignal(applicationContext)
            deferredRoots.forEach { initializer ->
                // The gate (frame await + timeout) lives OUTSIDE the untouched doAsyncInitialize:
                // this coroutine holds NO concurrency permit while suspended on the signal (the
                // permit is acquired later, inside doAsyncInitialize, after deps resolve) — so cap=1
                // never deadlocks. withTimeoutOrNull swallows only its OWN timeout (headless flush)
                // and rethrows any external CancellationException. Tracked in _deferredStartJobs, so
                // awaitAllStartJobs() never awaits it.
                coroutinesEngine.launchDeferredStartJob {
                    val fqn = initializer.name
                    val signalled =
                        tracer.asyncSection({ StartupTraceNames.of("deferred::awaitFirstFrame", fqn) }) {
                        withTimeoutOrNull(config.deferredStartupTimeoutMs) { signal.await() }
                    }
                    // android.os.Trace carries no metadata, so the outcome is encoded as a marker name.
                    tracer.mark {
                        StartupTraceNames.of(if (signalled != null) "marker::deferredSignal" else "marker::deferredTimeout", fqn)
                    }
                    doAsyncInitialize<Any>(initializer, HashSet(), startupGraph = true)
                }
            }
            coroutinesEngine.deferredStartJobs.takeLast(deferredRoots.size).forEach { job ->
                job.invokeOnCompletion { completeAsyncStartupRoot() }
            }
        }
    }

    private fun completeAsyncStartupRoot() {
        if (pendingAsyncStartupRoots.decrementAndGet() == 0) {
            transientAsyncStartupComponents.clear()
        }
    }

    /** Delegates to [StartupPlanner.asyncValidateAndGetRoots] / [StartupPlanner.computeEffectivePriorities]. */
    internal fun asyncValidateAndGetRoots(
        discovered: Set<Class<out StartupAsyncInitializer<*>>>,
        includeTransitiveDependencies: Boolean = false,
    ): Set<Class<out StartupAsyncInitializer<*>>> = planner.asyncValidateAndGetRoots(discovered, includeTransitiveDependencies)

    internal fun computeEffectivePriorities(
        discovered: Set<Class<out StartupAsyncInitializer<*>>>,
    ): Map<Class<out StartupAsyncInitializer<*>>, StartupPriority> = planner.computeEffectivePriorities(discovered)


    /** Milliseconds from process start to [uptimeMs] (same `uptimeMillis` clock); never negative. */
    private fun processStartOffsetMs(uptimeMs: Long): Long = (uptimeMs - Process.getStartUptimeMillis()).coerceAtLeast(0L)

    /**
     * Emits a single strict-mode warning per startup when the observed async-concurrency
     * [snapshot] exceeds [AppStartupConfig.strictModeConcurrencyThreshold].
     *
     * Only fires when [AppStartupConfig.strictModeCheckEnabled] is on. The [AtomicBoolean] guard
     * ensures at most one warning per initializer instance to avoid log spam when many
     * initializers breach the threshold simultaneously. The message reports the observed peak,
     * the configured threshold, the dispatcher in use, and the two remediations (ADR-4).
     */
    private fun maybeWarnStrictModeConcurrency(
        snapshot: Int,
        dispatcher: CoroutineDispatcher,
    ) {
        if (!config.strictModeCheckEnabled) return
        if (snapshot <= config.strictModeConcurrencyThreshold) return
        if (!strictModeConcurrencyWarned.compareAndSet(false, true)) return
        tracer.mark { StartupTraceNames.of("marker::strictModeConcurrency", null) }

        StartupExtensionLogger.warning {
            "Async startup concurrency reached $snapshot concurrently-active initializers, " +
                "exceeding strictModeConcurrencyThreshold=${config.strictModeConcurrencyThreshold} " +
                "on dispatcher $dispatcher. This can indicate a coroutine storm (thread " +
                "oversubscription). Remediation: set AppStartupConfig.maxConcurrentAsyncInitializers " +
                "to cap concurrency, or set a more suitable defaultAsyncDispatcher / per-initializer " +
                "dispatcher()."
        }
    }

    /**
     * Validates that [component] is a concrete, instantiable class before attempting reflection.
     *
     * Throwing early with a clear message avoids the cryptic [InstantiationException] that the
     * JVM would otherwise produce when [Class.newInstance] is called on an abstract class or interface.
     *
     * @throws StartupExtensionException if [component] is abstract or an interface.
     */
    private fun requireConcreteClass(component: Class<*>) {
        if (component.isInterface ||
            java.lang.reflect.Modifier
                .isAbstract(component.modifiers)
        ) {
            throw StartupExtensionException(
                "Cannot initialize ${component.name}: expected a concrete class " +
                    "but got an abstract class or interface.",
            )
        }
    }

    companion object {
        private const val STARTUP_LOG_TAG = "StartupLogger"
        private const val KEY_SYNC_INITIALIZER = "sync-initializer"
        private const val KEY_ASYNC_INITIALIZER = "async-initializer"
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
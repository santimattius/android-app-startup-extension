package io.github.santimattius.android.startup

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import io.github.santimattius.android.startup.dispatcher.DispatcherInstrumentation
import io.github.santimattius.android.startup.engine.AppStartupCoroutinesEngine
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.trace.StartupTraceNames
import io.github.santimattius.android.startup.trace.StartupTracer
import io.github.santimattius.android.startup.trace.asyncSection
import io.github.santimattius.android.startup.trace.closeAsyncSection
import io.github.santimattius.android.startup.trace.mark
import io.github.santimattius.android.startup.trace.openAsyncSection
import io.github.santimattius.android.startup.trace.section
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asynchronous initialization pipeline extracted from [AppStartupInitializer]: discovery of
 * [StartupAsyncInitializer] classes, root launching (eager and first-frame-gated deferred),
 * dependency-first suspending initialization and metric publication.
 *
 * Shared state ([initialized], [asyncDiscovered], [coroutinesEngine], [metricsBus], ...) is owned by
 * [AppStartupInitializer] and passed here by reference. State used only by this pipeline (in-flight
 * deduplication, transient startup components, pending root counter, strict-mode warning guard) is
 * owned here. [config] is read on every use so that the latest [AppStartupInitializer.configure]
 * call wins.
 */
internal class AsyncRunner(
    private val tracer: StartupTracer,
    private val registry: InitializerRegistry,
    private val initialized: ConcurrentHashMap<Class<*>, Any>,
    private val asyncDiscovered: MutableSet<Class<out StartupAsyncInitializer<*>>>,
    private val applicationContext: Context,
    private val metricsBus: StartupMetricsBus,
    private val planner: StartupPlanner,
    private val coroutinesEngine: AppStartupCoroutinesEngine,
    private val dispatcherInstrumentation: DispatcherInstrumentation,
    private val syncRunner: SyncRunner,
    private val config: () -> AppStartupConfig,
) {
    private data class AsyncInFlightKey(
        val component: Class<out StartupAsyncInitializer<*>>,
        val startupGraph: Boolean,
    )

    private val asyncInFlight = ConcurrentHashMap<AsyncInFlightKey, CompletableDeferred<Any>>()
    private val transientAsyncStartupComponents = ConcurrentHashMap<Class<out StartupAsyncInitializer<*>>, Any>()
    private val pendingAsyncStartupRoots = AtomicInteger(0)

    /**
     * Guards the strict-mode concurrency warning so it is emitted AT MOST ONCE per startup
     * (per initializer instance), regardless of how many initializers breach the threshold.
     */
    private val strictModeConcurrencyWarned = AtomicBoolean(false)

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
    suspend fun <T> doAsyncInitialize(
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
                                syncRunner.initializeUnderSyncLock<Any>(dependency, HashSet())
                            }
                    }

                    StartupExtensionLogger.info { "Initializing ${component.name}" }
                    val resolvedDispatcher =
                        asyncInitializer.dispatcher() ?: config().defaultAsyncDispatcher
                    val effectiveDispatcher: CoroutineDispatcher =
                        if (config().dispatcherInstrumentationEnabled) {
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
    fun asyncDiscoverAndInitialize(metadata: Bundle) {
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
            when (config().asyncInitializerStrategy) {
            is AsyncInitializerStrategy.Concurrent -> {
                tracer.section({ StartupTraceNames.of("resolveGraph::validateConcurrent", null) }) {
                    planner.asyncValidateAndGetRoots(asyncDiscovered, includeTransitiveDependencies = true)
                }
                asyncDiscovered
            }

                is AsyncInitializerStrategy.Validated -> {
                tracer.section({ StartupTraceNames.of("resolveGraph::validateRoots", null) }) {
                    planner.asyncValidateAndGetRoots(asyncDiscovered)
                }
        }
            }

        // Resolve priority inversions first (clamps a DEFERRED node up to its most-eager transitive
        // dependent and warns once), then partition the roots we are about to launch by EFFECTIVE
        // priority. Eager (NORMAL/CRITICAL — CRITICAL behaves like NORMAL in v1) roots launch
        // immediately on the critical path; DEFERRED roots are gated behind the first-frame signal.
        val effective =
            tracer.section({ StartupTraceNames.of("resolveGraph::effectivePriorities", null) }) {
            planner.computeEffectivePriorities(asyncDiscovered)
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
            val signal = config().firstFrameSignal ?: AndroidFirstFrameSignal(applicationContext)
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
                        withTimeoutOrNull(config().deferredStartupTimeoutMs) { signal.await() }
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
        if (!config().strictModeCheckEnabled) return
        if (snapshot <= config().strictModeConcurrencyThreshold) return
        if (!strictModeConcurrencyWarned.compareAndSet(false, true)) return
        tracer.mark { StartupTraceNames.of("marker::strictModeConcurrency", null) }

        StartupExtensionLogger.warning {
            "Async startup concurrency reached $snapshot concurrently-active initializers, " +
                "exceeding strictModeConcurrencyThreshold=${config().strictModeConcurrencyThreshold} " +
                "on dispatcher $dispatcher. This can indicate a coroutine storm (thread " +
                "oversubscription). Remediation: set AppStartupConfig.maxConcurrentAsyncInitializers " +
                "to cap concurrency, or set a more suitable defaultAsyncDispatcher / per-initializer " +
                "dispatcher()."
        }
    }

    private companion object {
        private const val KEY_ASYNC_INITIALIZER = "async-initializer"
    }
}

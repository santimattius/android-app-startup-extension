package io.github.santimattius.android.startup

import android.content.Context
import android.os.Bundle
import android.os.StrictMode
import android.os.SystemClock
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.github.santimattius.android.startup.trace.StartupTraceNames
import io.github.santimattius.android.startup.trace.StartupTracer
import io.github.santimattius.android.startup.trace.closeSection
import io.github.santimattius.android.startup.trace.openSection
import io.github.santimattius.android.startup.trace.section
import java.util.concurrent.ConcurrentHashMap

/**
 * Synchronous initialization pipeline extracted from [AppStartupInitializer]: discovery of
 * [StartupSyncInitializer] classes, lock-guarded depth-first initialization and metric publication.
 *
 * All state is owned by [AppStartupInitializer] and shared here by reference (never copied).
 * [config] is read on every use so that the latest [AppStartupInitializer.configure] call wins.
 */
internal class SyncRunner(
    private val tracer: StartupTracer,
    private val registry: InitializerRegistry,
    private val initialized: ConcurrentHashMap<Class<*>, Any>,
    private val syncDiscovered: MutableSet<Class<out StartupSyncInitializer<*>>>,
    private val applicationContext: Context,
    private val syncLocks: SyncLockCoordinator,
    private val metricsBus: StartupMetricsBus,
    private val planner: StartupPlanner,
    private val config: () -> AppStartupConfig,
) {
    fun <T> initializeUnderSyncLock(
        component: Class<out StartupSyncInitializer<*>>,
        initializing: MutableSet<Class<*>>,
    ): T {
        val waiting = tracer.openSection { StartupTraceNames.of("lockWait::sync", null) }
        var acquired = false
        try {
            syncLocks.acquireSyncLock(component)
            acquired = true
            tracer.closeSection(waiting)
            return (initialized[component] ?: doInitialize<T>(component, initializing)) as T
        } finally {
            if (!acquired) tracer.closeSection(waiting)
            if (acquired) syncLocks.releaseSyncLock(component)
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
                            if (config().strictModeCheckEnabled) {
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
     */
    fun syncDiscoverAndInitialize(metadata: Bundle) {
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
            when (config().syncOrderingStrategy) {
                is SyncOrderingStrategy.Lazy -> {
                    syncDiscovered.toList()
                }

                is SyncOrderingStrategy.Topological -> {
                tracer.section({ StartupTraceNames.of("resolveGraph::topological", null) }) {
                    planner.topologicalSort(syncDiscovered)
                }
        }
            }

        val initializing = mutableSetOf<Class<*>>()
        ordered.forEach { initializeUnderSyncLock<Any>(it, initializing) }
    }

    private companion object {
        private const val KEY_SYNC_INITIALIZER = "sync-initializer"
    }
}

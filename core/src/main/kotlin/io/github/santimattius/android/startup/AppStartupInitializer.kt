package io.github.santimattius.android.startup

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_META_DATA
import android.os.Bundle
import android.os.StrictMode
import android.os.Trace
import io.github.santimattius.android.startup.engine.AppStartupCoroutinesEngine
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
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
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    internal val coroutinesEngine = AppStartupCoroutinesEngine(coroutineDispatcher ?: Dispatchers.Default)

    private val metricsBus = StartupMetricsBus()

    /**
     * Hot [SharedFlow] that emits a [StartupMetric] after each initializer completes,
     * whether it succeeds or throws.
     *
     * All emitted values are replayed to late collectors: a collector joining after startup
     * finishes will still receive the full set of metrics from the startup sequence.
     *
     * **Thread of emission:** metrics are emitted from the initializer's own thread —
     * the Main Thread for sync initializers, and the initializer's `dispatcher()` for async ones.
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

    private val syncLock = Any()
    private val mutex = Mutex()

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
    fun <T : Any> initializeComponent(component: Class<out StartupSyncInitializer<T>>): T {
        return doInitialize(component)
    }

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
    fun isEagerlySyncInitialized(component: Class<out StartupSyncInitializer<*>>): Boolean {
        return syncDiscovered.contains(component)
    }

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
    fun isEagerlyAsyncInitialized(component: Class<out StartupAsyncInitializer<*>>): Boolean {
        return asyncDiscovered.contains(component)
    }

    /**
     * Initializes a StartupSyncInitializer component if it hasn't been initialized yet.
     *
     * This function ensures that a given `StartupSyncInitializer` component is initialized only once.
     * It uses a synchronized block and a shared map (`initialized`) to track which components have already been
     * initialized. If the component has already been initialized, it returns the previously initialized
     * instance. Otherwise, it recursively calls an internal initialization function (`doInitialize<Any>(component, HashSet())`)
     * to initialize the component and stores the result in the `initialized` map before returning it.
     *
     * @param component The Class object representing the StartupSyncInitializer component to initialize.
     *                  This must be a class that extends StartupSyncInitializer.
     * @param T The expected type of the initialized component. This is used for type casting the returned value.
     *          The actual initialized type is determined by the `component` parameter.
     * @return The initialized instance of the `StartupSyncInitializer` component, cast to type `T`.
     * @throws ClassCastException if the initialized object cannot be cast to type `T`.
     * @throws IllegalStateException if the initialization process encounters any issue.
     */
    fun <T> doInitialize(component: Class<out StartupSyncInitializer<*>>): T {
        var result: Any?
        synchronized(syncLock) {
            result = initialized[component]
            if (result == null) {
                result = doInitialize<T>(component, HashSet())
            }
        }
        return result as T
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
        return mutex.withLock {
            initialized[component] ?: doAsyncInitialize(component, HashSet())
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
    internal fun discoverAndInitialize(
        initializationProvider: Class<out InitializationProvider>
    ) {
        try {
            Trace.beginSection(SECTION_NAME)
            val provider = ComponentName(applicationContext, initializationProvider)
            val providerInfo = Trace.beginSection(TRACE_PROVIDER_INFO).let {
                try {
                    applicationContext.packageManager.getProviderInfo(provider, GET_META_DATA)
                } finally {
                    Trace.endSection()
                }
            }
            val metadata = providerInfo.metaData
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
        initializing: MutableSet<Class<*>>
    ): T {
        Trace.beginSection("$TRACE_PREFIX${component.simpleName}")
        try {
            require(component !in initializing) { "Cannot initialize ${component.name}. Cycle detected." }

            var shouldRetain = true
            val result = initialized.getOrPut(component) {
                requireConcreteClass(component)
                initializing.add(component)
                try {
                    val initializer =
                        component.getDeclaredConstructor()
                            .newInstance() as StartupSyncInitializer<*>

                    shouldRetain = initializer.retainAfterStartup()

                    initializer.dependencies()
                        .filterNot { initialized.containsKey(it) }
                        .forEach { doInitialize<Any>(it, initializing) }

                    StartupExtensionLogger.info("Initializing ${component.name}")
                    val startNs = System.nanoTime()
                    var success = false
                    // Install a StrictMode policy that detects accidental I/O or disk
                    // access inside create(). Only active when the caller opts in via
                    // enableStrictModeCheck(true). The original policy is restored after
                    // every create() call regardless of outcome.
                    val savedPolicy = if (config.strictModeCheckEnabled) {
                        StrictMode.getThreadPolicy().also {
                            StrictMode.setThreadPolicy(
                                StrictMode.ThreadPolicy.Builder()
                                    .detectAll()
                                    .penaltyLog()
                                    .build()
                            )
                        }
                    } else null
                    try {
                        val result = initializer.create(applicationContext)
                        success = true
                        StartupExtensionLogger.info("Initialized ${component.name}")
                        result
                    } finally {
                        savedPolicy?.let { StrictMode.setThreadPolicy(it) }
                        metricsBus.publish(
                            StartupMetric(
                                initializerName = component.simpleName,
                                durationMs = (System.nanoTime() - startNs) / 1_000_000L,
                                isAsync = false,
                                success = success,
                            )
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
            Trace.endSection()
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
        initializing: MutableSet<Class<*>>
    ): T {
        Trace.beginSection("$TRACE_PREFIX${component.simpleName}")
        try {
            require(component !in initializing) { "Cannot initialize ${component.name}. Cycle detected." }

            var shouldRetain = true
            val result = initialized.getOrPut(component) {
                requireConcreteClass(component)
                initializing.add(component)
                try {
                    val initializer = component.getDeclaredConstructor()
                        .newInstance()
                        .also { it as StartupAsyncInitializer<*> }

                    val asyncInitializer = (initializer as StartupAsyncInitializer<*>)
                    shouldRetain = asyncInitializer.retainAfterStartup()

                    asyncInitializer.dependencies()
                        .filterNot(initialized::containsKey)
                        .forEach { doAsyncInitialize<Any>(it, initializing) }

                    // Resolve cross-type dependencies: sync initializers that must complete
                    // before this async initializer's create() is invoked.
                    asyncInitializer.syncDependencies()
                        .filterNot(initialized::containsKey)
                        .forEach { doInitialize<Any>(it) }

                    StartupExtensionLogger.info("Initializing ${component.name}")
                    val startNs = System.nanoTime()
                    var success = false
                    var wasCancelled = false
                    val initializerDispatcher = asyncInitializer.dispatcher()
                    try {
                        val result = withContext(initializerDispatcher) {
                            asyncInitializer.create(applicationContext)
                        }
                        success = true
                        StartupExtensionLogger.info("Initialized ${component.name}")
                        result
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        wasCancelled = true
                        throw e
                    } finally {
                        metricsBus.publish(
                            StartupMetric(
                                initializerName = component.simpleName,
                                durationMs = (System.nanoTime() - startNs) / 1_000_000L,
                                isAsync = true,
                                success = success,
                                wasCancelled = wasCancelled,
                            )
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (throwable: Throwable) {
                    StartupExtensionLogger.error(
                        "Error initializing ${component.name}: ${throwable.message}",
                        throwable
                    )
                    throw StartupExtensionException(throwable)
                } finally {
                    initializing.remove(component)
                }
            } as T
            if (!shouldRetain) initialized.remove(component)
            return result
        } finally {
            Trace.endSection()
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
        syncDiscovered.addAll(
            metadata.keySet()
                .filter { key -> metadata.getString(key) == KEY_SYNC_INITIALIZER }
                .mapNotNull { key ->
                    try {
                        Class.forName(key, false, applicationContext.classLoader)
                            .takeIf { StartupSyncInitializer::class.java.isAssignableFrom(it) }
                            ?.let { it as Class<out StartupSyncInitializer<*>> }
                            ?.also { StartupExtensionLogger.info("Discovered $key") }
                    } catch (e: ClassNotFoundException) {
                        throw StartupExtensionException(e)
                    }
                }
        )

        val ordered = when (config.syncOrderingStrategy) {
            is SyncOrderingStrategy.Lazy -> syncDiscovered.toList()
            is SyncOrderingStrategy.Topological -> topologicalSort(syncDiscovered)
        }

        val initializing = mutableSetOf<Class<*>>()
        ordered.forEach { doInitialize(it, initializing) }
    }

    /**
     * Sorts [discovered] sync initializers using Kahn's algorithm so that every dependency
     * appears before the nodes that depend on it.
     *
     * Cycle detection is a by-product of Kahn's: if not all nodes are processed the remaining
     * ones must form a cycle. The exception is thrown before any [StartupSyncInitializer.create]
     * is called, making the failure state clean and easy to diagnose.
     *
     * @throws StartupExtensionException if a cycle is found, naming all participating nodes.
     */
    internal fun topologicalSort(
        discovered: Set<Class<out StartupSyncInitializer<*>>>,
    ): List<Class<out StartupSyncInitializer<*>>> {
        val inDegree = mutableMapOf<Class<out StartupSyncInitializer<*>>, Int>()
        val adjacency = mutableMapOf<Class<out StartupSyncInitializer<*>>, MutableList<Class<out StartupSyncInitializer<*>>>>()

        discovered.forEach { cls ->
            inDegree.getOrPut(cls) { 0 }
            adjacency.getOrPut(cls) { mutableListOf() }

            cls.getDeclaredConstructor().newInstance()
                .let { it as StartupSyncInitializer<*> }
                .dependencies()
                .filter { it in discovered }
                .forEach { dep ->
                    adjacency.getOrPut(dep) { mutableListOf() }.add(cls)
                    inDegree[cls] = (inDegree[cls] ?: 0) + 1
                    inDegree.getOrPut(dep) { 0 }
                }
        }

        val queue = ArrayDeque<Class<out StartupSyncInitializer<*>>>()
        inDegree.filterValues { it == 0 }.keys.forEach { queue.add(it) }

        val sorted = mutableListOf<Class<out StartupSyncInitializer<*>>>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted.add(current)
            adjacency[current]?.forEach { neighbor ->
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) queue.add(neighbor)
            }
        }

        if (sorted.size != discovered.size) {
            val cycleNodes = (discovered - sorted.toSet()).joinToString(", ") { it.simpleName }
            throw StartupExtensionException("Cycle detected in sync initializers: [$cycleNodes]")
        }

        return sorted
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
    private fun asyncDiscoverAndInitialize(metadata: Bundle) {
        asyncDiscovered.addAll(
            metadata.keySet()
                .filter { key -> metadata.getString(key) == KEY_ASYNC_INITIALIZER }
                .mapNotNull { key ->
                    try {
                        Class.forName(key, false, applicationContext.classLoader)
                            .takeIf { StartupAsyncInitializer::class.java.isAssignableFrom(it) }
                            ?.let { it as Class<out StartupAsyncInitializer<*>> }
                            ?.also { StartupExtensionLogger.info("Discovered $key") }
                    } catch (e: ClassNotFoundException) {
                        throw StartupExtensionException(e)
                    }
                }
        )
        val toLaunch = when (config.asyncInitializerStrategy) {
            is AsyncInitializerStrategy.Concurrent -> asyncDiscovered
            is AsyncInitializerStrategy.Validated -> asyncValidateAndGetRoots(asyncDiscovered)
        }

        toLaunch.forEach { initializer ->
            // Each job gets its own initializing set: cycle detection is per-chain,
            // and sharing a mutable set across concurrent coroutines is a data race.
            coroutinesEngine.launchStartJob { doAsyncInitialize<Any>(initializer, HashSet()) }
        }
    }

    /**
     * Validates the async-to-async dependency graph (built from [StartupAsyncInitializer.dependencies]
     * only — [StartupAsyncInitializer.syncDependencies] are excluded because all sync initializers
     * discovered in the manifest have already completed before this is called) and returns the
     * **root** nodes: async initializers that no other async initializer depends on.
     *
     * Cycle detection uses Kahn's algorithm as a by-product: if not all nodes are processed,
     * the remaining ones form a cycle. The exception is thrown before any coroutine is launched
     * or any [StartupAsyncInitializer.create] is called.
     *
     * Launching only the roots is sufficient — each root coroutine resolves its transitive
     * async dependencies recursively via [doAsyncInitialize], eliminating redundant coroutine launches.
     *
     * @throws StartupExtensionException if a cycle is found, naming all participating nodes.
     */
    internal fun asyncValidateAndGetRoots(
        discovered: Set<Class<out StartupAsyncInitializer<*>>>,
    ): Set<Class<out StartupAsyncInitializer<*>>> {
        val inDegree = mutableMapOf<Class<out StartupAsyncInitializer<*>>, Int>()
        val adjacency = mutableMapOf<Class<out StartupAsyncInitializer<*>>, MutableList<Class<out StartupAsyncInitializer<*>>>>()
        val allDeclaredDependencies = mutableSetOf<Class<out StartupAsyncInitializer<*>>>()

        discovered.forEach { cls ->
            inDegree.getOrPut(cls) { 0 }
            adjacency.getOrPut(cls) { mutableListOf() }

            cls.getDeclaredConstructor().newInstance()
                .let { it as StartupAsyncInitializer<*> }
                .dependencies()
                .filter { it in discovered }
                .forEach { dep ->
                    allDeclaredDependencies.add(dep)
                    adjacency.getOrPut(dep) { mutableListOf() }.add(cls)
                    inDegree[cls] = (inDegree[cls] ?: 0) + 1
                    inDegree.getOrPut(dep) { 0 }
                }
        }

        val queue = ArrayDeque<Class<out StartupAsyncInitializer<*>>>()
        inDegree.filterValues { it == 0 }.keys.forEach { queue.add(it) }

        val processed = mutableListOf<Class<out StartupAsyncInitializer<*>>>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            processed.add(current)
            adjacency[current]?.forEach { neighbor ->
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) queue.add(neighbor)
            }
        }

        if (processed.size != discovered.size) {
            val cycleNodes = (discovered - processed.toSet()).joinToString(", ") { it.simpleName }
            throw StartupExtensionException("Cycle detected in async initializers: [$cycleNodes]")
        }

        return discovered - allDeclaredDependencies
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
        if (component.isInterface || java.lang.reflect.Modifier.isAbstract(component.modifiers)) {
            throw StartupExtensionException(
                "Cannot initialize ${component.name}: expected a concrete class " +
                    "but got an abstract class or interface."
            )
        }
    }

    companion object {
        private const val KEY_SYNC_INITIALIZER = "sync-initializer"
        private const val KEY_ASYNC_INITIALIZER = "async-initializer"
        private const val SECTION_NAME = "StartupExtension"
        private const val TRACE_PREFIX = "Startup::"
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
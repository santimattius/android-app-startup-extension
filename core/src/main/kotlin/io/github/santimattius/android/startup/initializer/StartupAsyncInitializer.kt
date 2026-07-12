package io.github.santimattius.android.startup.initializer

import android.content.Context
import io.github.santimattius.android.startup.StartupPriority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * An interface for asynchronously initializing a component or service during application startup.
 *
 * Implementations of this interface are responsible for creating an instance of type [T]
 * in an asynchronous manner, potentially performing time-consuming operations such as
 * network requests or database interactions.  The initialization process is managed by a
 * startup manager, ensuring dependencies are created in the correct order.
 *
 * **Key Concepts:**
 *
 * *   **Asynchronous Initialization:** The [create] method is a `suspend` function, allowing
 *     for non-blocking operations. This is crucial for preventing the application's main
 *     thread from being blocked during startup.
 * *   **Dependency Management:** The [dependencies] method allows you to specify other
 *     `StartupAsyncInitializer`s that must be initialized before this one. This creates
 *     a directed acyclic graph (DAG) of initializers, enabling the startup manager to
 *     initialize them in the correct order.
 * *   **Context Awareness:** The [create] method receives an [Context] object, providing
 *     access to application resources, file storage, and other Android system services.
 * *   **Type Safety:** The generic type parameter [T] defines the type of object created
 *      by the initializer. This ensures type safety when accessing the initialized object.
 *
 * **Usage:**
 *
 * 1.  **Implement the interface:** Create a class that implements `StartupAsyncInitializer<T>`,
 *     replacing `T` with the type of object you want to create.
 * 2.  **Implement `create`:** Implement the `create(context: Context)` method to perform the
 *     asynchronous initialization logic and return an instance of [T].
 * 3.  **Implement `dependencies` (optional):** If your initializer depends on other initializers,
 *     override the `dependencies()` method to return a list of their classes.
 * 4. **Register Initializer:** The class that implement this interface should be registered
 *  in the startup manager.
 *
 * **Example:**
 *
 * ```kotlin
 * class DatabaseInitializer : StartupAsyncInitializer<Database> {
 *     override suspend fun create(context: Context): Database {
 *         // Perform asynchronous database initialization (e.g., schema creation)
 * */
interface StartupAsyncInitializer<T : Any> {

    /**
     * Creates an instance of type [T] using the provided [Context].
     *
     * This function is a suspend function, meaning it can be paused and resumed,
     * typically used for operations that might take some time, such as network
     * requests or database operations. The specific creation process depends on
     * the implementation details of [T] and should be documented further if applicable.
     *
     * @param context The Android [Context] to be used during the creation process.
     *                This might be needed for accessing resources, preferences,
     *                or other system-level functionalities.
     * @return An instance of type [T] that has been successfully created.
     * @throws Exception If an error occurs during the creation process. Specific exceptions
     *                   thrown depend on the implementation of the function and type [T].
     *                   It is recommended to catch potential exceptions and handle them
     *                   appropriately.
     */
    suspend fun create(context: Context): T

    /**
     * Returns a list of dependencies that this initializer relies on.
     *
     * The returned list contains the classes of other [StartupAsyncInitializer] instances
     * that must be initialized before this one. If this initializer does not depend on any
     * other initializers, it should return an empty list.
     *
     * Note that the dependencies are only for [StartupAsyncInitializer] and not for regular
     * initializers. If this initializer depends on regular initializers then it should be
     * mentioned in the documentation.
     *
     * @return A list of [Class] objects representing the dependencies of this initializer.
     *         Returns an empty list if there are no dependencies.
     */
    fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> = emptyList()

    /**
     * Returns a list of synchronous initializers that must complete before this async initializer's
     * [create] is invoked.
     *
     * Use this to express cross-type dependencies: an async initializer that requires the result of
     * a sync initializer declares that sync initializer here. The startup manager guarantees that
     * every class in this list has been fully initialized (its [StartupSyncInitializer.create]
     * returned) before [create] is called on this instance.
     *
     * Each declared class is initialized at most once — if multiple async initializers share a sync
     * dependency it will not be re-created.
     *
     * @return A list of [StartupSyncInitializer] classes that this initializer depends on.
     *         Returns an empty list if there are no sync dependencies.
     */
    fun syncDependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()

    /**
     * Returns the [CoroutineDispatcher] on which [create] will be executed, or `null` to defer
     * to the library-wide default.
     *
     * Override this to pin the initializer's work to a specific thread pool. For example,
     * return [Dispatchers.IO] for disk or network I/O, or a dedicated single-thread dispatcher
     * for components that require thread confinement.
     *
     * ## Resolution precedence
     *
     * The dispatcher `create()` actually runs on is resolved in this order:
     * 1. This method's non-null return value (per-instance override — highest precedence).
     * 2. `AppStartupConfig.defaultAsyncDispatcher` (library-wide default).
     * 3. [Dispatchers.Default] (the ultimate fallback that `defaultAsyncDispatcher` itself defaults to).
     *
     * Returning `null` (the default) means "I have no preference — use the library default". This
     * nullable sentinel is what lets the library distinguish an unoverridden initializer from one
     * that explicitly chose [Dispatchers.Default].
     */
    fun dispatcher(): CoroutineDispatcher? = null

    /**
     * Returns the [StartupPriority] that controls **when** this initializer is launched during startup.
     *
     * ## Default — [StartupPriority.NORMAL] (eager)
     *
     * With no override the initializer is treated as `NORMAL` and launched immediately on the eager
     * critical path, exactly like every initializer prior to this feature. This is an **additive
     * default method** (same source+binary-compatible pattern as the [dispatcher] sentinel), so
     * existing initializers keep their current behavior with zero changes.
     *
     * ## Override to [StartupPriority.DEFERRED]
     *
     * Return `DEFERRED` for work that is safe to run **after the first frame is drawn** — for
     * example, analytics warmers or prefetchers that must not compete with UI-critical startup.
     * A `DEFERRED` root's [create] is not invoked until the injected
     * [io.github.santimattius.android.startup.FirstFrameSignal] resolves, or the configured
     * `deferredStartupTimeoutMs` elapses (headless/no-UI fallback).
     *
     * ## [StartupPriority.CRITICAL] in v1
     *
     * `CRITICAL` currently behaves **identically to `NORMAL`** (it partitions as eager, does not
     * bypass the concurrency cap, and is not ordered ahead of `NORMAL`). It is reserved for a future
     * strict scheduler; the ordinal distinction only feeds the priority-inversion clamp today.
     *
     * > **Priority inversion:** if an eager (`NORMAL`/`CRITICAL`) initializer depends transitively on
     * > a `DEFERRED` one, the library clamps the `DEFERRED` dependency's *effective* priority up to its
     * > most-eager dependent (so it runs eagerly, not after the frame) and logs a one-time warning.
     */
    fun priority(): StartupPriority = StartupPriority.NORMAL

    /**
     * Whether the instance returned by [create] should be kept in the initializer registry
     * after startup completes.
     *
     * ## Default behavior — `true` (retain)
     *
     * The result of [create] is stored in an internal `ConcurrentHashMap` keyed by the
     * initializer class and held for the entire lifetime of the application. This is the
     * correct behavior for components that other parts of the app retrieve post-startup
     * via `AppStartupInitializer.getInstance(context).initializeComponent(...)`.
     *
     * ## Override to `false` (transient)
     *
     * Return `false` when [create] produces an object that is only needed **during the
     * startup sequence itself** — for example, a prefetch job, a cache warmer, or any
     * component whose value lies entirely in its side effects. The library will release
     * the reference from its registry as soon as the coroutine finishes, allowing the
     * GC to collect the object.
     *
     * ```kotlin
     * class CacheWarmupInitializer : StartupAsyncInitializer<Unit> {
     *     override suspend fun create(context: Context) {
     *         ImageCache.warmup(context)
     *     }
     *     // Result is meaningless after warmup; don't hold it in memory.
     *     override fun retainAfterStartup(): Boolean = false
     * }
     * ```
     *
     * > **Note:** A transient initializer whose result is requested again after startup
     * > (via `initializeComponent`) will re-execute [create]. Make sure [create] is safe
     * > to call more than once, or avoid requesting transient initializers post-startup.
     */
    fun retainAfterStartup(): Boolean = true
}

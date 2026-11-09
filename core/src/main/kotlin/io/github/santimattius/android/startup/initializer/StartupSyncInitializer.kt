package io.github.santimattius.android.startup.initializer

import android.content.Context

/**
 * Interface for synchronously initializing components during application startup.
 *
 * This interface allows you to define components that need to be created and initialized
 * synchronously during the application's startup phase. It provides a mechanism to specify
 * dependencies between initializers, ensuring that they are created in the correct order.
 *
 * Implementations of this interface are responsible for creating an instance of a specific type [T]
 * and can define other [StartupSyncInitializer]s as dependencies.
 *
 * **Key Features:**
 *
 * *   **Synchronous Initialization:** The caller executes `create()` and waits for it to return,
 *     ensuring that the component is fully initialized before execution proceeds. This is suitable
 *     for components that are critical for the app's core functionality.
 * *   **Dependency Management:** The `dependencies()` method allows you to declare other
 *     [StartupSyncInitializer]s that must be initialized before the current one. This enables
 *     complex initialization sequences.
 * *   **Caller-thread execution:** On-demand initialization runs on the calling thread. Provider-driven
 *     startup runs on the main thread because Android invokes `ContentProvider.onCreate()` there.
 * *   **Early Execution:** By implementing this interface, the component will be initialized as early as possible during app startup.
 *
 * **Usage:**
 *
 * 1.  Implement this interface for each component you want to initialize synchronously.
 * 2.  Implement the `create(context: Context)` method to construct and initialize your component.
 * 3.  Override the `dependencies()` method if your component relies on other components initialized
 *     by other `StartupSyncInitializer`s. Return a list of the dependent initializer classes.
 * 4. An initialisation component (that is not part of this code) will call the create() function on startup and handle dependencies.
 *
 * **Example:**
 *
 * ```kotlin
 * class AnalyticsInitializer : StartupSyncInitializer<Analytics> {
 *     override fun create(context: Context): Analytics {
 *         // Initialize the Analytics component.
 *         return Analytics.getInstance(context)
 *     }
 *
 *     override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> {
 *         // Analytics might depend on a LoggingInitializer
 *          */
interface StartupSyncInitializer<T : Any> {

    /**
     * Creates an instance of the desired type [T].
     *
     * This function runs synchronously on the thread that requests it: the caller waits until it returns.
     * `InitializationProvider` discovery naturally invokes it on the main thread because Android calls
     * [android.content.ContentProvider.onCreate] there; on-demand initialization and sync dependencies of
     * async initializers run in their current caller context instead.
     *
     * Keep provider-driven initialization short to avoid blocking the main thread and risking an ANR.
     * For heavy work, implement [StartupAsyncInitializer] and select an appropriate
     * [StartupAsyncInitializer.dispatcher] (for example, `Dispatchers.IO`). A dispatcher can also be
     * `Main`, `Unconfined`, or custom, so async execution is dispatcher-controlled rather than guaranteed
     * to use a different thread.
     *
     * To detect accidental I/O during development, call
     * `AppStartupInitializer.enableStrictModeCheck(BuildConfig.DEBUG)` from
     * [android.app.Application.attachBaseContext]. Violations appear in logcat.
     *
     * @param context The application context.
     * @return An instance of type [T], fully initialized and ready for use.
     */
    fun create(context: Context): T

    /**
     * Returns a list of classes that this initializer depends on.
     *
     * The returned list represents the dependencies that must be initialized
     * before this initializer. This is used to establish the correct
     * initialization order when multiple initializers are present.
     *
     * For instance, if `InitializerB` depends on `InitializerA`,
     * then `InitializerA` must be present in the list returned by `dependencies()`
     * in `InitializerB`.
     *
     * If an initializer does not have any dependencies, an empty list should be returned.
     *
     * @return A list of classes representing the dependencies of this initializer. Each class must implement
     *         [StartupSyncInitializer].
     */
    fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()

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
     * startup sequence itself** — for example, a configuration builder, a migration runner,
     * or a bootstrapper that has no value once it has executed its side effects. The library
     * will release the reference from its registry as soon as initialization finishes,
     * allowing the GC to collect the object.
     *
     * ```kotlin
     * class MigrationInitializer : StartupSyncInitializer<Unit> {
     *     override fun create(context: Context) {
     *         DatabaseMigrationRunner(context).runPendingMigrations()
     *     }
     *     // Unit result has no value post-startup; release it immediately.
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

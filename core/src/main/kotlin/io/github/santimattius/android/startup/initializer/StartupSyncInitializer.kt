package io.github.santimattius.android.startup.initializer

import android.content.Context
import androidx.annotation.MainThread

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
 * *   **Synchronous Initialization:**  The `create()` method is called synchronously on the main thread,
 *     ensuring that the component is fully initialized before the application proceeds. This is suitable
 *     for components that are critical for the app's core functionality.
 * *   **Dependency Management:** The `dependencies()` method allows you to declare other
 *     [StartupSyncInitializer]s that must be initialized before the current one. This enables
 *     complex initialization sequences.
 * *   **Main Thread Execution:** The `create()` method is annotated with `@MainThread`, indicating that it
 *     should be executed on the main application thread.
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
     * This function runs **synchronously on the main thread** inside [android.content.ContentProvider.onCreate].
     * The Android system will trigger an ANR if the main thread is blocked for more than 5 seconds.
     *
     * **Do NOT perform any of the following inside `create()`:**
     * - Disk reads or writes
     * - Network requests
     * - Database queries
     * - Any blocking operation or long-running computation
     *
     * If your component requires heavy initialization, implement [StartupAsyncInitializer] instead and
     * move the blocking work to [StartupAsyncInitializer.dispatcher] (e.g. `Dispatchers.IO`).
     *
     * To detect accidental violations at development time, call
     * `AppStartupInitializer.enableStrictModeCheck(BuildConfig.DEBUG)` from
     * [android.app.Application.attachBaseContext]. Any blocking I/O will appear as a
     * `StrictMode` violation in logcat.
     *
     * @param context The application context.
     * @return An instance of type [T], fully initialized and ready for use.
     */
    @MainThread
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

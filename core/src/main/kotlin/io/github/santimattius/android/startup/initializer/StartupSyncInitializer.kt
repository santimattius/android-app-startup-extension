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
     * This function is designed to be called on the main (UI) thread.  It handles the
     * necessary context and resource acquisition required for proper object instantiation.
     *
     * @param context The application context, typically obtained from an Activity or Application.
     *                This context is used for accessing resources, services, and other system-level
     *                information.
     * @return An instance of type [T], fully initialized and ready for use.
     * @throws IllegalStateException If the necessary resources or dependencies cannot be accessed
     *                              through the provided context, or if any initialization process fails.
     * @throws IllegalArgumentException if the provided context is invalid or null.
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
}

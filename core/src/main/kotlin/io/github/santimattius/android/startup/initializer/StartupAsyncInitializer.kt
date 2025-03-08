package io.github.santimattius.android.startup.initializer

import android.content.Context

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
}

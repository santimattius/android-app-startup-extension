package io.github.santimattius.android.startup.initializer

import android.content.Context

interface StartupSyncInitializer<T: Any> {
    /**
     * Initializes a library component within the application [Context].
     *
     * @param context The application context.
     */
    fun create(context: Context): T

    /**
     * Gets a list of this initializer's dependencies.
     *
     * Dependencies are initialized before the dependent initializer. For
     * example, if initializer A defines initializer B as a dependency, B is
     * initialized before A.
     *
     * @return A list of initializer dependencies.
     */
    fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()
}

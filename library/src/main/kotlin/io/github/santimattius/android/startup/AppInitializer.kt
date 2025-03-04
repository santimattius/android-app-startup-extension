package io.github.santimattius.android.startup

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_META_DATA
import android.os.Bundle
import android.os.Trace
import io.github.santimattius.android.startup.StartupExtensionLogger.i
import io.github.santimattius.android.startup.engine.AppStartupCoroutinesEngine
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

class AppInitializer internal constructor(
    context: Context,
    coroutineDispatcher: CoroutineDispatcher? = null
) {

    internal val coroutinesEngine = AppStartupCoroutinesEngine(coroutineDispatcher)

    private val initialized: MutableMap<Class<*>, Any> = HashMap()
    private val syncDiscovered: MutableSet<Class<out StartupSyncInitializer<*>>> = HashSet()
    private val asyncDiscovered: MutableSet<Class<out StartupAsyncInitializer<*>>> = HashSet()

    private val applicationContext: Context = context.applicationContext

    @Suppress("unused")
    fun <T : Any> initializeComponent(component: Class<out StartupSyncInitializer<T>>): T {
        return doInitialize(component)
    }

    fun isEagerlyInitialized(component: Class<out StartupSyncInitializer<*>?>): Boolean {
        return syncDiscovered.contains(component)
    }

    fun <T> doInitialize(component: Class<out StartupSyncInitializer<*>>): T {
        var result: Any?
        synchronized(sLock) {
            result = initialized[component]
            if (result == null) {
                result = doInitialize<Any>(component, HashSet())
            }
        }
        return result as T
    }

    suspend fun <T> doInitialize(component: Class<out StartupAsyncInitializer<*>>): T {
        return mutex.withLock {
            initialized[component] ?: doAsyncInitialize(component, HashSet())

        } as T

    }

    fun discoverAndInitialize(
        initializationProvider: Class<out InitializationProvider?>
    ) {
        try {
            Trace.beginSection(SECTION_NAME)
            val provider = ComponentName(applicationContext, initializationProvider)
            val providerInfo = applicationContext.packageManager
                .getProviderInfo(provider, GET_META_DATA)
            val metadata = providerInfo.metaData
            syncDiscoverAndInitialize(metadata)
            asyncDiscoverAndInitialize(metadata)
        } catch (exception: PackageManager.NameNotFoundException) {
            throw StartupExtensionException(exception)
        } finally {
            Trace.endSection()
        }
    }

    private fun <T> doInitialize(
        component: Class<out StartupSyncInitializer<*>>,
        initializing: MutableSet<Class<*>>
    ): T {
        Trace.beginSection(component.simpleName)
        try {
            require(component !in initializing) { "Cannot initialize ${component.name}. Cycle detected." }

            return initialized.getOrPut(component) {
                initializing.add(component)
                try {
                    val initializer =
                        component.getDeclaredConstructor()
                            .newInstance() as StartupSyncInitializer<*>

                    initializer.dependencies()
                        .filterNot { initialized.containsKey(it) }
                        .forEach { doInitialize<Any>(it, initializing) }

                    if (StartupExtensionLogger.DEBUG) i("Initializing ${component.name}")
                    val result = initializer.create(applicationContext)
                    if (StartupExtensionLogger.DEBUG) i("Initialized ${component.name}")

                    result
                } catch (throwable: Throwable) {
                    throw StartupExtensionException(throwable)
                } finally {
                    initializing.remove(component)
                }
            } as T
        } finally {
            Trace.endSection()
        }
    }

    private suspend fun <T> doAsyncInitialize(
        component: Class<out StartupAsyncInitializer<*>>,
        initializing: MutableSet<Class<*>>
    ): T {
        Trace.beginSection(component.simpleName)
        try {
            require(component !in initializing) { "Cannot initialize ${component.name}. Cycle detected." }

            return initialized.getOrPut(component) {
                initializing.add(component)
                try {
                    val initializer =
                        component.getDeclaredConstructor()
                            .newInstance() as StartupAsyncInitializer<*>

                    initializer.dependencies()
                        .filterNot { initialized.containsKey(it) }
                        .forEach { doAsyncInitialize<Any>(it, initializing) }

                    if (StartupExtensionLogger.DEBUG) i("Initializing ${component.name}")
                    val result = initializer.create(applicationContext)
                    if (StartupExtensionLogger.DEBUG) i("Initialized ${component.name}")

                    result
                } catch (throwable: Throwable) {
                    throw StartupExtensionException(throwable)
                } finally {
                    initializing.remove(component)
                }
            } as T
        } finally {
            Trace.endSection()
        }
    }

    private fun syncDiscoverAndInitialize(metadata: Bundle) {
        val initializing = mutableSetOf<Class<*>>()

        syncDiscovered.addAll(
            metadata.keySet()
                .filter { key -> metadata.getString(key) == "sync-initializer" }
                .mapNotNull { key ->
                    try {
                        Class.forName(key)
                            .takeIf { StartupSyncInitializer::class.java.isAssignableFrom(it) }
                            ?.let { it as Class<out StartupSyncInitializer<*>> }
                            ?.also { if (StartupExtensionLogger.DEBUG) i("Discovered $key") }
                    } catch (e: ClassNotFoundException) {
                        throw StartupExtensionException(e)
                    }
                }
        )
        syncDiscovered.forEach { doInitialize(it, initializing) }
    }

    private fun asyncDiscoverAndInitialize(metadata: Bundle) {
        val initializing = mutableSetOf<Class<*>>()

        asyncDiscovered.addAll(
            metadata.keySet()
                .filter { key -> metadata.getString(key) == "async-initializer" }
                .mapNotNull { key ->
                    try {
                        Class.forName(key)
                            .takeIf { StartupAsyncInitializer::class.java.isAssignableFrom(it) }
                            ?.let { it as Class<out StartupAsyncInitializer<*>> }
                            ?.also { if (StartupExtensionLogger.DEBUG) i("Discovered $key") }
                    } catch (e: ClassNotFoundException) {
                        throw StartupExtensionException(e)
                    }
                }
        )
        asyncDiscovered.forEach { initializer ->
            coroutinesEngine.launchStartJob { doAsyncInitialize<Any>(initializer, initializing) }
        }
    }


    companion object {
        // Tracing
        private const val SECTION_NAME = "Startup"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var sInstance: AppInitializer? = null

        private val sLock = Any()
        private val mutex = Mutex()

        fun getInstance(context: Context): AppInitializer {
            if (sInstance == null) {
                synchronized(sLock) {
                    if (sInstance == null) {
                        sInstance = AppInitializer(context)
                    }
                }
            }
            return sInstance!!
        }

        fun setDelegate(delegate: AppInitializer) {
            synchronized(sLock) {
                sInstance = delegate
            }
        }
    }
}
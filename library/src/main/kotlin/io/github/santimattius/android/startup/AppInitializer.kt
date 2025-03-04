package io.github.santimattius.android.startup

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_META_DATA
import android.os.Bundle
import android.os.Trace
import io.github.santimattius.android.startup.StartupExtensionLogger.i
import kotlin.concurrent.Volatile

class AppInitializer internal constructor(context: Context) {

    private val mInitialized: MutableMap<Class<*>, Any> = HashMap()
    private val mDiscovered: MutableSet<Class<out StartupInitializer<*>>> = HashSet()
    private val mContext: Context = context.applicationContext

    @Suppress("unused")
    fun <T : Any> initializeComponent(component: Class<out StartupInitializer<T>>): T {
        return doInitialize(component)
    }

    fun isEagerlyInitialized(component: Class<out StartupInitializer<*>?>): Boolean {
        return mDiscovered.contains(component)
    }

    fun <T> doInitialize(component: Class<out StartupInitializer<*>>): T {
        var result: Any?
        synchronized(sLock) {
            result = mInitialized[component]
            if (result == null) {
                result = doInitialize<Any>(component, HashSet())
            }
        }
        return result as T
    }

    private fun <T> doInitialize(
        component: Class<out StartupInitializer<*>>,
        initializing: MutableSet<Class<*>>
    ): T {
        Trace.beginSection(component.simpleName)
        try {
            require(component !in initializing) { "Cannot initialize ${component.name}. Cycle detected." }

            return mInitialized.getOrPut(component) {
                initializing.add(component)
                try {
                    val initializer =
                        component.getDeclaredConstructor().newInstance() as StartupInitializer<*>

                    initializer.dependencies()
                        .filterNot { mInitialized.containsKey(it) }
                        .forEach { doInitialize<Any>(it, initializing) }

                    if (StartupExtensionLogger.DEBUG) i("Initializing ${component.name}")
                    val result = initializer.create(mContext)
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


    fun discoverAndInitialize(
        initializationProvider: Class<out InitializationProvider?>
    ) {
        try {
            Trace.beginSection(SECTION_NAME)
            val provider = ComponentName(mContext, initializationProvider)
            val providerInfo = mContext.packageManager
                .getProviderInfo(provider, GET_META_DATA)
            val metadata = providerInfo.metaData
            discoverAndInitialize(metadata)
        } catch (exception: PackageManager.NameNotFoundException) {
            throw StartupExtensionException(exception)
        } finally {
            Trace.endSection()
        }
    }

    private fun discoverAndInitialize(metadata: Bundle) {
        val initializing = mutableSetOf<Class<*>>()

        mDiscovered.addAll(
            metadata.keySet()
                .filter { key -> metadata.getString(key) == "androidx.startup" }
                .mapNotNull { key ->
                    try {
                        Class.forName(key)
                            .takeIf { StartupInitializer::class.java.isAssignableFrom(it) }
                            ?.let { it as Class<out StartupInitializer<*>> }
                            ?.also { if (StartupExtensionLogger.DEBUG) i("Discovered $key") }
                    } catch (e: ClassNotFoundException) {
                        throw StartupExtensionException(e)
                    }
                }
        )
        mDiscovered.forEach { doInitialize<Any>(it, initializing) }
    }


    companion object {
        // Tracing
        private const val SECTION_NAME = "Startup"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var sInstance: AppInitializer? = null

        private val sLock = Any()

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

        /**
         * Sets an [AppInitializer] delegate. Useful in the context of testing.
         *
         * @param delegate The instance of [AppInitializer] to be used as a delegate.
         */
        fun setDelegate(delegate: AppInitializer) {
            synchronized(sLock) {
                sInstance = delegate
            }
        }
    }
}
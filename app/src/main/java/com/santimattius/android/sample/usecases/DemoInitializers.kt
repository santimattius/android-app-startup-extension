package com.santimattius.android.sample.usecases

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Initializers for the on-demand use cases. None of them is declared in a manifest: they are only
 * ever created through `AppStartupInitializer.doInitialize` / `initializeComponent`.
 */
object DemoCounters {
    private val creates = ConcurrentHashMap<Class<*>, AtomicInteger>()
    private val events = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Called from every `create()`; returns how many times that class has been created so far. */
    fun created(cls: Class<*>): Int {
        events += cls.simpleName
        return creates.getOrPut(cls) { AtomicInteger() }.incrementAndGet()
    }

    fun count(cls: Class<*>): Int = creates[cls]?.get() ?: 0

    /** Order in which `create()` bodies ran since the last [drainEvents]. */
    fun drainEvents(): List<String> = synchronized(events) { events.toList().also { events.clear() } }
}

private const val ASYNC_DEMO_DELAY_MS = 150L

/** Retained (default): `create()` runs once, later requests return the cached instance. */
class RetainedSyncDemo : StartupSyncInitializer<String> {
    override fun create(context: Context) = "retained-sync#${DemoCounters.created(javaClass)}"
}

/** Transient: dropped from the registry right after `create()`, so every request creates again. */
class TransientSyncDemo : StartupSyncInitializer<String> {
    override fun create(context: Context) = "transient-sync#${DemoCounters.created(javaClass)}"
    override fun retainAfterStartup() = false
}

class RetainedAsyncDemo : StartupAsyncInitializer<String> {
    override suspend fun create(context: Context): String {
        delay(ASYNC_DEMO_DELAY_MS)
        return "retained-async#${DemoCounters.created(javaClass)}@${Thread.currentThread().name}"
    }
}

class TransientAsyncDemo : StartupAsyncInitializer<String> {
    override suspend fun create(context: Context): String {
        delay(ASYNC_DEMO_DELAY_MS)
        return "transient-async#${DemoCounters.created(javaClass)}"
    }
    override fun retainAfterStartup() = false
}

// Dependency chains. Every class is transient so each run shows the full creation order again.

/** Sync -> sync: [SyncParentDemo] lists this in `dependencies()`. */
class SyncChildDemo : StartupSyncInitializer<Unit> {
    override fun create(context: Context) { DemoCounters.created(javaClass) }
    override fun retainAfterStartup() = false
}

class SyncParentDemo : StartupSyncInitializer<Unit> {
    override fun create(context: Context) { DemoCounters.created(javaClass) }
    override fun dependencies() = listOf(SyncChildDemo::class.java)
    override fun retainAfterStartup() = false
}

/** Async -> async through `dependencies()`. */
class AsyncChildDemo : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) { DemoCounters.created(javaClass) }
    override fun retainAfterStartup() = false
}

class AsyncParentDemo : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) { DemoCounters.created(javaClass) }
    override fun dependencies() = listOf(AsyncChildDemo::class.java)
    override fun retainAfterStartup() = false
}

/** Async -> sync through `syncDependencies()`. */
class AsyncNeedsSyncDemo : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) { DemoCounters.created(javaClass) }
    override fun syncDependencies() = listOf(SyncChildDemo::class.java)
    override fun retainAfterStartup() = false
}

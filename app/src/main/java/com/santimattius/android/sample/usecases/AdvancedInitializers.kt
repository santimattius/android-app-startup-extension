package com.santimattius.android.sample.usecases

import android.content.Context
import io.github.santimattius.android.startup.StartupPriority
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** Initializers for dispatcher / priority / failure / exception use cases. All are transient. */
private val singleThread: CoroutineDispatcher by lazy {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "demo-single").apply { isDaemon = true } }
    // A readable toString() becomes the metric's dispatcherName.
    object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
        override fun toString() = "SingleThread(demo-single)"
    }
}

abstract class TransientAsyncBase : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) { delay(50) }
    override fun retainAfterStartup() = false
}

/** Per-instance `dispatcher()` override: highest precedence over the library default. */
class IoDispatcherDemo : TransientAsyncBase() {
    override fun dispatcher(): CoroutineDispatcher = Dispatchers.IO
}

class SingleThreadDispatcherDemo : TransientAsyncBase() {
    override fun dispatcher(): CoroutineDispatcher = singleThread
}

class CriticalPriorityDemo : TransientAsyncBase() {
    override fun priority() = StartupPriority.CRITICAL
}

class DeferredPriorityDemo : TransientAsyncBase() {
    override fun priority() = StartupPriority.DEFERRED
}

class FailingAsyncDemo : TransientAsyncBase() {
    override suspend fun create(context: Context) { error("demo failure") }
}

abstract class AbstractSyncDemo : StartupSyncInitializer<Unit>
abstract class AbstractAsyncDemo : StartupAsyncInitializer<Unit>

class CycleASyncDemo : StartupSyncInitializer<Unit> {
    override fun create(context: Context) = Unit
    override fun dependencies() = listOf(CycleBSyncDemo::class.java)
    override fun retainAfterStartup() = false
}

class CycleBSyncDemo : StartupSyncInitializer<Unit> {
    override fun create(context: Context) = Unit
    override fun dependencies() = listOf(CycleASyncDemo::class.java)
    override fun retainAfterStartup() = false
}

class CycleAAsyncDemo : TransientAsyncBase() {
    override fun dependencies() = listOf(CycleBAsyncDemo::class.java)
}

class CycleBAsyncDemo : TransientAsyncBase() {
    override fun dependencies() = listOf(CycleAAsyncDemo::class.java)
}

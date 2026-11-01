package io.github.santimattius.android.startup.trace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.InitializationProvider
import io.github.santimattius.android.startup.awaitAllStartJobs
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec: startup-async-tracing P2 lockWait, awaitAll and onDemand trigger encoding (tasks 4.5-4.6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class TraceLockAwaitTriggerTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val tracer = RecordingStartupTracer()

    private fun node(cls: Class<*>) = "Startup::${cls.name}"
    private fun phase(phase: String, cls: Class<*>) = "Startup::$phase::${cls.name}"

    @Before
    fun setUp() {
        gate = CountDownLatch(1)
        holderEntered = CountDownLatch(1)
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        gate.countDown()
        AppStartupInitializer.configure { }
    }

    private fun awaitEvent(predicate: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!predicate()) delay(5) }
    }

    @Test
    fun `contended async lockWait spans the wait and an uncontended one is adjacent`() = runBlocking {
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)
        val holder = launch(Dispatchers.IO) { initializer.doInitialize<String>(AsyncHolder::class.java) }
        holderEntered.await(5, TimeUnit.SECONDS)
        val waiter = launch(Dispatchers.IO) { initializer.doInitialize<String>(AsyncOther::class.java) }
        val wait = phase("lockWait::async", AsyncOther::class.java)
        awaitEvent { tracer.asyncBeginIndex(wait) >= 0 }

        assertEquals("the waiter must still be queued behind the holder", -1, tracer.asyncEndIndex(wait))
        gate.countDown()
        holder.join()
        waiter.join()

        assertTrue(tracer.asyncEndIndex(wait) > tracer.asyncEndIndex(node(AsyncHolder::class.java)))
        // The holder acquired a free lock: nothing happened between its lockWait begin and end.
        val free = phase("lockWait::async", AsyncHolder::class.java)
        assertEquals(tracer.asyncBeginIndex(free) + 1, tracer.asyncEndIndex(free))
        assertTrue(tracer.asyncBalanced())
    }

    @Test
    fun `contended sync lockWait spans the wait on the waiting thread`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)
        val holder = Thread { initializer.doInitialize<String>(SyncHolder::class.java) }.also { it.start() }
        holderEntered.await(5, TimeUnit.SECONDS)
        val waiter = Thread({ initializer.doInitialize<String>(SyncOther::class.java) }, "lock-waiter").also { it.start() }
        val wait = "Startup::lockWait::sync"
        awaitEvent {
            tracer.events.any { it is RecordingStartupTracer.Event.Begin && it.name == wait && it.thread == "lock-waiter" }
        }

        assertTrue("the waiter must still be blocked", tracer.events.none {
            it is RecordingStartupTracer.Event.End && it.name == wait && it.thread == "lock-waiter"
        })
        gate.countDown()
        holder.join(5_000)
        waiter.join(5_000)

        val waitEnd = tracer.events.indexOfFirst {
            it is RecordingStartupTracer.Event.End && it.name == wait && it.thread == "lock-waiter"
        }
        assertTrue(waitEnd > tracer.syncEndIndex(node(SyncHolder::class.java)))
        assertTrue(tracer.syncBalanced())
    }

    @Test
    fun `awaitAll slice covers the suspension of both overloads`() {
        listOf<suspend (AppStartupInitializer) -> Unit>(
            { it.awaitAllStartJobs() },
            { it.awaitAllStartJobs(5_000L) },
        ).forEach { await ->
            tracer.events.clear()
            val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)
            val release = CompletableDeferred<Unit>()
            initializer.coroutinesEngine.launchStartJob { release.await() }
            runBlocking {
                val caller = launch(Dispatchers.IO) { await(initializer) }
                awaitEvent { tracer.asyncBeginIndex("Startup::awaitAll") >= 0 }
                assertEquals(-1, tracer.asyncEndIndex("Startup::awaitAll"))

                release.complete(Unit)
                caller.join()
            }
            assertTrue(tracer.asyncEndIndex("Startup::awaitAll") > tracer.asyncBeginIndex("Startup::awaitAll"))
            assertTrue(tracer.asyncBalanced())
        }
    }

    @Test
    fun `public entry points are onDemand while startup and the internal sync dependency path are not`() {
        AppStartupInitializer.configure { defaultAsyncDispatcher = Dispatchers.IO }
        val bundle = Bundle().apply {
            putString(AsyncWithSyncDep::class.java.name, "async-initializer")
            putString(SyncOther::class.java.name, "sync-initializer")
        }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runBlocking { initializer.awaitAllStartJobs() }

        assertTrue("the internal path must really have run", tracer.syncBeginIndex(node(SyncDep::class.java)) >= 0)
        assertTrue(tracer.events.none { it.name.startsWith("Startup::onDemand::") })

        initializer.initializeComponent(SyncOnDemand::class.java)
        runBlocking { initializer.doInitialize<String>(AsyncOther::class.java) }

        val syncDemand = phase("onDemand", SyncOnDemand::class.java)
        assertTrue(tracer.syncBeginIndex(syncDemand) in 0 until tracer.syncBeginIndex(node(SyncOnDemand::class.java)))
        assertTrue(tracer.syncEndIndex(syncDemand) > tracer.syncEndIndex(node(SyncOnDemand::class.java)))
        val asyncDemand = phase("onDemand", AsyncOther::class.java)
        assertTrue(tracer.asyncBeginIndex(asyncDemand) in 0 until tracer.asyncBeginIndex(node(AsyncOther::class.java)))
        assertTrue(tracer.asyncEndIndex(asyncDemand) > tracer.asyncEndIndex(node(AsyncOther::class.java)))
        assertTrue(tracer.syncBalanced() && tracer.asyncBalanced())
    }

    @Test
    fun `disabled tracer records nothing for lockWait awaitAll and onDemand`() = runBlocking {
        tracer.enabled = false
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)

        initializer.initializeComponent(SyncOnDemand::class.java)
        initializer.doInitialize<String>(AsyncOther::class.java)
        initializer.awaitAllStartJobs()

        assertTrue(tracer.events.isEmpty())
    }

    companion object {
        @Volatile
        var gate = CountDownLatch(1)

        @Volatile
        var holderEntered = CountDownLatch(1)

        private fun hold() {
            holderEntered.countDown()
            gate.await(5, TimeUnit.SECONDS)
        }
    }

    class SyncHolder : StartupSyncInitializer<String> {
        override fun create(context: Context) = "holder".also { hold() }
    }

    class SyncOther : StartupSyncInitializer<String> {
        override fun create(context: Context) = "other"
    }

    class SyncOnDemand : StartupSyncInitializer<String> {
        override fun create(context: Context) = "demand"
    }

    class SyncDep : StartupSyncInitializer<String> {
        override fun create(context: Context) = "dep"
    }

    class AsyncHolder : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "holder".also { hold() }
    }

    class AsyncOther : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "other"
    }

    class AsyncWithSyncDep : StartupAsyncInitializer<String> {
        override fun syncDependencies(): List<Class<out StartupSyncInitializer<*>>> = listOf(SyncDep::class.java)
        override suspend fun create(context: Context) = "withDep"
    }
}

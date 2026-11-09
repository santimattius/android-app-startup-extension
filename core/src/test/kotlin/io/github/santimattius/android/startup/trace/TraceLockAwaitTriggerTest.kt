package io.github.santimattius.android.startup.trace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.InitializationProvider
import io.github.santimattius.android.startup.awaitAllStartJobs
import io.github.santimattius.android.startup.engine.AppStartupCoroutinesEngine
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Spec: startup-async-tracing P2 lockWait, awaitAll and onDemand trigger encoding (tasks 4.5-4.6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class TraceLockAwaitTriggerTest {
    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val tracer = RecordingStartupTracer()

    private fun node(cls: Class<*>) = "Startup::${cls.name}"

    private fun phase(
        phase: String,
        cls: Class<*>,
    ) = "Startup::$phase::${cls.name}"

    @Before
    fun setUp() {
        gate = CountDownLatch(1)
        holderEntered = CountDownLatch(1)
        cycleDependenciesEntered = CountDownLatch(2)
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

    private fun awaitEvent(predicate: () -> Boolean) {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var observed = predicate()
        while (!observed && System.nanoTime() < deadlineNs) {
            Thread.sleep(5)
            observed = predicate()
        }
        assertTrue("Timed out waiting for trace event", observed)
    }

    @Test
    fun `contended async lockWait spans the wait and an uncontended one is adjacent`() =
        runBlocking {
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
    fun `background sync initialization traces lockWait on its caller thread`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)
        val callerThread = "lock-waiter"
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, callerThread) }

        try {
            assertEquals(
                "other",
                executor.submit<String> { initializer.doInitialize(SyncOther::class.java) }.get(5, TimeUnit.SECONDS),
            )
        } finally {
            executor.shutdownNow()
        }

        val wait = "Startup::lockWait::sync"
        val waitBegin =
            tracer.events.indexOfFirst {
                it is RecordingStartupTracer.Event.Begin && it.name == wait && it.thread == callerThread
            }
        val waitEnd =
            tracer.events.indexOfFirst {
                it is RecordingStartupTracer.Event.End && it.name == wait && it.thread == callerThread
            }
        assertTrue("lockWait must be traced on the sync caller thread", waitBegin >= 0)
        assertEquals("an uncontended caller-thread lockWait must be adjacent", waitBegin + 1, waitEnd)
        assertTrue(tracer.syncBalanced())
    }

    @Test
    fun `contended sync lockWait remains balanced after ownership is released`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)
        val executor = newExecutor("trace-sync-contention")
        val ownerThread = AtomicReference<Thread>()
        val waiterThread = AtomicReference<Thread>()
        val waiterStarted = CountDownLatch(1)

        try {
            val owner =
                executor.submit<String> {
                    ownerThread.set(Thread.currentThread())
                    initializer.doInitialize(SyncHolder::class.java)
                }
            assertTrue(holderEntered.await(5, TimeUnit.SECONDS))
            val waiter =
                executor.submit<String> {
                    waiterThread.set(Thread.currentThread())
                    waiterStarted.countDown()
                    initializer.doInitialize(SyncHolder::class.java)
                }
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))
            waitForWaitingState(waiterThread)
            gate.countDown()

            assertEquals("holder", owner.get(5, TimeUnit.SECONDS))
            assertEquals("holder", waiter.get(5, TimeUnit.SECONDS))
            val ownerNodeEnd =
                tracer.events.indexOfLast {
                    it is RecordingStartupTracer.Event.End &&
                        it.name == node(SyncHolder::class.java) &&
                        it.thread == ownerThread.get().name
                }
            val waiterLockWaitEnd =
                tracer.events.indexOfLast {
                    it is RecordingStartupTracer.Event.End &&
                        it.name == "Startup::lockWait::sync" &&
                        it.thread == waiterThread.get().name
                }
            assertTrue("waiter lockWait must span real ownership contention", waiterLockWaitEnd > ownerNodeEnd)
            assertTrue(tracer.syncBalanced())
        } finally {
            gate.countDown()
            shutdown(executor)
        }
    }

    @Test
    fun `sync lockWait remains balanced after cross-thread cycle failure`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)
        val executor = newExecutor("trace-sync-cycle")

        try {
            val first = executor.submit<String> { initializer.doInitialize(CrossThreadCycleA::class.java) }
            val second = executor.submit<String> { initializer.doInitialize(CrossThreadCycleB::class.java) }

            val firstFailure = runCatching { first.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            val secondFailure = runCatching { second.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue(firstFailure is ExecutionException)
            assertTrue(secondFailure is ExecutionException)
            assertTrue(
                firstFailure?.hasCauseMessage("Cross-thread cycle detected") == true ||
                    secondFailure?.hasCauseMessage("Cross-thread cycle detected") == true,
            )
            assertTrue(tracer.syncBalanced())
        } finally {
            shutdown(executor)
        }
    }

    @Test
    fun `sync lockWait remains balanced when a waiting caller is interrupted`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)
        val executor = newExecutor("trace-sync-interrupt")
        val waiterThread = AtomicReference<Thread>()
        val waiterStarted = CountDownLatch(1)

        try {
            val owner = executor.submit<String> { initializer.doInitialize(SyncHolder::class.java) }
            assertTrue(holderEntered.await(5, TimeUnit.SECONDS))
            val waiter =
                executor.submit<String> {
                    waiterThread.set(Thread.currentThread())
                    waiterStarted.countDown()
                    initializer.doInitialize(SyncHolder::class.java)
                }
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))
            waitForWaitingState(waiterThread)
            waiterThread.get().interrupt()

            val failure = runCatching { waiter.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue(failure is ExecutionException)
            assertTrue(failure?.cause is IllegalStateException)
            assertTrue(failure?.cause?.cause is InterruptedException)
            assertTrue(
                "interrupted waiter must close lockWait before the owner is released",
                tracer.events.any {
                    it is RecordingStartupTracer.Event.End &&
                        it.name == "Startup::lockWait::sync" &&
                        it.thread == waiterThread.get().name
                },
            )
            gate.countDown()
            assertEquals("holder", owner.get(5, TimeUnit.SECONDS))
            assertTrue(tracer.syncBalanced())
        } finally {
            gate.countDown()
            shutdown(executor)
        }
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
    fun `an uncontended sync lockWait begins and ends with nothing in between`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        initializer.doInitialize<String>(SyncOther::class.java)

        val wait = "Startup::lockWait::sync"
        assertTrue(tracer.syncBeginIndex(wait) >= 0)
        assertEquals(tracer.syncBeginIndex(wait) + 1, tracer.syncEndIndex(wait))
        assertTrue(tracer.syncBalanced())
    }

    @Test
    fun `awaitAll slice is closed when the jobs fail or the awaiter is cancelled in both overloads`() {
        listOf<suspend (AppStartupCoroutinesEngine) -> Unit>(
            { it.awaitAllStartJobs() },
            { it.awaitAllStartJobs(5_000L) },
        ).forEach { await ->
            tracer.events.clear()
            val failing = AppStartupCoroutinesEngine(Dispatchers.IO, tracer = tracer)
            failing.launchStartJob<Unit> { error("boom") }
            assertTrue(runCatching { runBlocking { await(failing) } }.exceptionOrNull() is IllegalStateException)
            assertTrue("closed after failure", tracer.asyncEndIndex("Startup::awaitAll") > tracer.asyncBeginIndex("Startup::awaitAll"))
            assertTrue(tracer.asyncBalanced())

            tracer.events.clear()
            val pending = AppStartupCoroutinesEngine(Dispatchers.IO, tracer = tracer)
            pending.launchStartJob { CompletableDeferred<Unit>().await() }
            runBlocking {
                val caller = launch(Dispatchers.IO) { await(pending) }
                awaitEvent { tracer.asyncBeginIndex("Startup::awaitAll") >= 0 }
                assertEquals(-1, tracer.asyncEndIndex("Startup::awaitAll"))
                caller.cancelAndJoin()
            }
            assertTrue("closed after cancellation", tracer.asyncEndIndex("Startup::awaitAll") > tracer.asyncBeginIndex("Startup::awaitAll"))
            assertTrue(tracer.asyncBalanced())
            pending.cancel()
        }
    }

    @Test
    fun `awaitAll slice is closed when the timed overload times out`() {
        val engine = AppStartupCoroutinesEngine(Dispatchers.IO, tracer = tracer)
        engine.launchStartJob { CompletableDeferred<Unit>().await() }

        runCatching { runBlocking { engine.awaitAllStartJobs(50L) } }

        assertTrue(tracer.asyncEndIndex("Startup::awaitAll") > tracer.asyncBeginIndex("Startup::awaitAll"))
        assertTrue(tracer.asyncBalanced())
        engine.cancel()
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun `public entry points are onDemand while startup and the internal sync dependency path are not`() {
        AppStartupInitializer.configure { defaultAsyncDispatcher = Dispatchers.IO }
        val bundle =
            Bundle().apply {
                putString(AsyncWithSyncDep::class.java.name, "async-initializer")
                putString(SyncOther::class.java.name, "sync-initializer")
            }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        val executor = Executors.newSingleThreadExecutor()
        try {
            awaitWithMainLooper(
                executor.submit<Unit> {
                    runBlocking { initializer.awaitAllStartJobs() }
                },
            )
        } finally {
            executor.shutdownNow()
        }

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
    fun `disabled tracer records nothing for lockWait awaitAll and onDemand`() =
        runBlocking {
            tracer.enabled = false
            val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)

            initializer.initializeComponent(SyncOnDemand::class.java)
            initializer.doInitialize<String>(AsyncOther::class.java)
            initializer.awaitAllStartJobs()

            assertTrue(tracer.events.isEmpty())
        }

    private fun waitForWaitingState(thread: AtomicReference<Thread>) {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.get().state != Thread.State.WAITING && System.nanoTime() < deadlineNs) {
            Thread.yield()
        }
        assertEquals(Thread.State.WAITING, thread.get().state)
    }

    private fun Throwable.hasCauseMessage(expected: String): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.message?.contains(expected) == true) return true
            current = current.cause
        }
        return false
    }

    private fun newExecutor(name: String): java.util.concurrent.ExecutorService {
        val sequence = AtomicInteger()
        return Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "$name-${sequence.incrementAndGet()}")
        }
    }

    private fun shutdown(executor: java.util.concurrent.ExecutorService) {
        executor.shutdownNow()
        assertTrue("executor did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun <T> awaitWithMainLooper(future: Future<T>): T {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!future.isDone && System.nanoTime() < deadlineNs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    companion object {
        @Volatile
        var gate = CountDownLatch(1)

        @Volatile
        var holderEntered = CountDownLatch(1)

        @Volatile
        var cycleDependenciesEntered = CountDownLatch(2)

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

    class CrossThreadCycleA : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"

        override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> {
            cycleDependenciesEntered.countDown()
            check(cycleDependenciesEntered.await(5, TimeUnit.SECONDS))
            return listOf(CrossThreadCycleB::class.java)
        }
    }

    class CrossThreadCycleB : StartupSyncInitializer<String> {
        override fun create(context: Context) = "b"

        override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> {
            cycleDependenciesEntered.countDown()
            check(cycleDependenciesEntered.await(5, TimeUnit.SECONDS))
            return listOf(CrossThreadCycleA::class.java)
        }
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

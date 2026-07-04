package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec: bounded-async-concurrency + startup-observability (tasks 6.1/6.2).
 *
 * Exercises the REAL concurrent launch path (`discoverAndInitialize` → `launchStartJob` →
 * private `doAsyncInitialize`), the only path that runs multiple `create()` bodies
 * concurrently (the public on-demand `doInitialize` serializes via a mutex). This is where
 * the semaphore gate and the enriched [StartupMetric] instrumentation actually matter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AsyncConcurrencyGatingTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        active.set(0)
        peak.set(0)
        capReached = CountDownLatch(2)
        gate = CountDownLatch(1)
        // Captured at AppStartupInitializer construction — must be stubbed before constructing.
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        // Release any coroutine still parked on the gate so a failing assertion cannot hang teardown.
        gate.countDown()
        AppStartupInitializer.configure { }
    }

    // ── Contention: cap bounds concurrentActiveCount and queueDelayMs > 0 ─────

    @Test
    fun `cap bounds concurrentActiveCount and records queue delay under contention`() {
        AppStartupInitializer.configure {
            maxConcurrentAsyncInitializers = 2
            defaultAsyncDispatcher = Dispatchers.IO
        }
        val initializer = AppStartupInitializer(context, Dispatchers.IO)
        stubManifest(
            Barrier1::class.java, Barrier2::class.java, Barrier3::class.java,
            Barrier4::class.java, Barrier5::class.java,
        )

        initializer.discoverAndInitialize(InitializationProvider::class.java)

        // Wait until exactly cap (2) initializers are concurrently blocked in create(),
        // hold them briefly so the 3 gated initializers accrue a measurable queue delay,
        // then release everything.
        assertTrue("Two initializers should reach create() concurrently under cap=2", capReached.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }

        val metrics = initializer.metricsFlow.replayCache
        assertEquals("All five initializers must emit a metric", 5, metrics.size)
        assertTrue(
            "concurrentActiveCount must never exceed the cap of 2, got: ${metrics.map { it.concurrentActiveCount }}",
            metrics.all { it.concurrentActiveCount in 1..2 },
        )
        assertTrue(
            "Peak observed concurrency in create() must not exceed the cap of 2, got ${peak.get()}",
            peak.get() <= 2,
        )
        assertTrue(
            "At least one gated initializer must record queueDelayMs > 0, got: ${metrics.map { it.queueDelayMs }}",
            metrics.any { it.queueDelayMs > 0 },
        )
    }

    // ── dispatcherName / threadName reflect actual execution ─────────────────

    @Test
    fun `metric captures dispatcherName and threadName of actual execution`() = runTest {
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(NamedDispatcherInitializer::class.java)

        val metric = initializer.metricsFlow.replayCache.single()
        assertEquals(namedDispatcher.toString(), metric.dispatcherName)
        assertTrue(
            "threadName must reflect the thread create() actually ran on, got: ${metric.threadName}",
            metric.threadName.contains(NAMED_THREAD),
        )
        assertEquals(1, metric.concurrentActiveCount)
    }

    // ── cap=1 + dependency chain still completes (no deadlock) ────────────────

    @Test
    fun `cap of 1 with a dependency chain still completes`() = runTest {
        AppStartupInitializer.configure { maxConcurrentAsyncInitializers = 1 }
        val initializer = AppStartupInitializer(context)

        val result = initializer.doInitialize<String>(ParentInit::class.java)

        assertEquals("parent(child)", result)
        // Both the child (resolved before the parent acquires its permit) and the parent ran.
        assertEquals(2, initializer.metricsFlow.replayCache.size)
    }

    // ── Manifest stubbing helper ──────────────────────────────────────────────

    private fun stubManifest(vararg classes: Class<*>) {
        val bundle = Bundle().apply {
            classes.forEach { putString(it.name, "async-initializer") }
        }
        val providerInfo = ProviderInfo().apply { metaData = bundle }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns providerInfo
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    companion object {
        private const val NAMED_THREAD = "named-metric-dispatcher"

        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        @Volatile
        var capReached = CountDownLatch(2)

        @Volatile
        var gate = CountDownLatch(1)

        val namedDispatcher: ExecutorCoroutineDispatcher = Executors
            .newSingleThreadExecutor { r -> Thread(r, NAMED_THREAD).also { it.isDaemon = true } }
            .asCoroutineDispatcher()
    }

    /** Blocks in create() until the test opens the gate, tracking peak concurrency. */
    private open class BarrierInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            val current = active.incrementAndGet()
            peak.updateAndGet { existing -> maxOf(existing, current) }
            capReached.countDown()
            gate.await(5, TimeUnit.SECONDS)
            active.decrementAndGet()
            return "ok"
        }
    }

    private class Barrier1 : BarrierInitializer()
    private class Barrier2 : BarrierInitializer()
    private class Barrier3 : BarrierInitializer()
    private class Barrier4 : BarrierInitializer()
    private class Barrier5 : BarrierInitializer()

    private class NamedDispatcherInitializer : StartupAsyncInitializer<String> {
        override fun dispatcher(): CoroutineDispatcher = namedDispatcher
        override suspend fun create(context: Context): String = "named"
    }

    /** child (no deps) → parent. Under cap=1 the child must fully release its permit before the parent acquires. */
    private class ChildInit : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "child"
    }

    private class ParentInit : StartupAsyncInitializer<String> {
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(ChildInit::class.java)

        override suspend fun create(context: Context): String = "parent(child)"
    }
}

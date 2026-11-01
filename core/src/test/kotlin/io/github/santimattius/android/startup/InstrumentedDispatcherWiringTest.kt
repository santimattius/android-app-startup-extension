package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.dispatcher.InstrumentedDispatcher
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.trace.RecordingStartupTracer
import io.github.santimattius.android.startup.trace.StartupTraceNames
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Spec: startup-dispatcher-instrumentation wiring (opt-in, precedence, failure, permitWaitMs). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class InstrumentedDispatcherWiringTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        interceptors.clear()
        firstRunning = CountDownLatch(1)
        gate = CountDownLatch(1)
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

    @Test
    fun `default config leaves dispatchers unwrapped and keeps no snapshot`() = runTest {
        AppStartupInitializer.configure { defaultAsyncDispatcher = configDispatcher }
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(PlainInit::class.java)

        assertTrue(interceptors.getValue(PlainInit::class.java) === configDispatcher)
        assertTrue(initializer.dispatcherSnapshots().isEmpty())
        assertTrue(initializer.metricsFlow.replayCache.single().dispatcherName.isNotEmpty())
    }

    @Test
    fun `enabled keeps precedence - per-instance dispatcher wins and is the one measured`() = runTest {
        AppStartupInitializer.configure {
            defaultAsyncDispatcher = configDispatcher
            dispatcherInstrumentationEnabled = true
        }
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(OverrideInit::class.java)
        initializer.doInitialize<String>(PlainInit::class.java)

        val override = interceptors.getValue(OverrideInit::class.java) as InstrumentedDispatcher
        val snapshots = initializer.dispatcherSnapshots()
        val configured = interceptors.getValue(PlainInit::class.java) as InstrumentedDispatcher
        // Executor toString() carries live pool stats, so the label captured at wrap time is the key.
        assertEquals(setOf(override.label, configured.label), snapshots.keys)
        assertTrue(snapshots.values.all { it.totalDispatched >= 1 })
        val names = initializer.metricsFlow.replayCache.map { it.dispatcherName }
        assertEquals(listOf(override.label, configured.label), names)
    }

    @Test
    fun `initializers sharing an effective dispatcher share one wrapper and entry`() = runTest {
        AppStartupInitializer.configure {
            defaultAsyncDispatcher = configDispatcher
            dispatcherInstrumentationEnabled = true
        }
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(PlainInit::class.java)
        initializer.doInitialize<String>(OtherPlainInit::class.java)

        assertTrue(interceptors.getValue(PlainInit::class.java) === interceptors.getValue(OtherPlainInit::class.java))
        assertEquals(1, initializer.dispatcherSnapshots().size)
    }

    @Test
    fun `failing initializer keeps siblings alive, records error and does not corrupt counters`() {
        AppStartupInitializer.configure {
            defaultAsyncDispatcher = configDispatcher
            dispatcherInstrumentationEnabled = true
        }
        val initializer = AppStartupInitializer(context)
        stubManifest(FailingInit::class.java, PlainInit::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCatching { runBlocking { initializer.awaitAllStartJobs() } }

        val metrics = initializer.metricsFlow.replayCache.associateBy { it.initializerName }
        assertFalse(metrics.getValue("FailingInit").success)
        assertNotNull(metrics.getValue("FailingInit").errorType)
        assertTrue(metrics.getValue("PlainInit").success)
        val snapshot = initializer.dispatcherSnapshots().values.single()
        assertTrue(snapshot.peakConcurrency >= 1)
        assertTrue(snapshot.totalDispatched >= 2)
    }

    @Test
    fun `cap of 1 separates semaphore wait from dispatcher queue with instrumentation on`() {
        AppStartupInitializer.configure {
            maxConcurrentAsyncInitializers = 1
            defaultAsyncDispatcher = roomyDispatcher
            dispatcherInstrumentationEnabled = true
        }
        val tracer = RecordingStartupTracer()
        val initializer = AppStartupInitializer(context, tracer = tracer)

        val metrics = runCapOneScenario(initializer, tracer)

        val waiter = metrics.maxByOrNull { it.permitWaitMs }!!
        assertTrue("waiter must wait for the permit, got ${waiter.permitWaitMs}", waiter.permitWaitMs >= WAIT_FLOOR_MS - 5)
        assertTrue(metrics.all { it.permitWaitMs <= it.queueDelayMs })
        assertTrue(
            "dispatcher queue must stay below the semaphore wait",
            initializer.dispatcherSnapshots().values.single().maxQueueDelayMs < waiter.permitWaitMs,
        )
    }

    @Test
    fun `permitWaitMs is measured even with instrumentation off`() {
        AppStartupInitializer.configure {
            maxConcurrentAsyncInitializers = 1
            defaultAsyncDispatcher = roomyDispatcher
        }
        val tracer = RecordingStartupTracer()
        val initializer = AppStartupInitializer(context, tracer = tracer)

        val metrics = runCapOneScenario(initializer, tracer)

        assertTrue(metrics.maxOf { it.permitWaitMs } >= WAIT_FLOOR_MS - 5)
        assertTrue(initializer.dispatcherSnapshots().isEmpty())
    }

    @Test
    fun `permitWaitMs stays within queueDelayMs when cancelled after the permit and before the body starts`() {
        val parking = ParkingDispatcher()
        AppStartupInitializer.configure {
            maxConcurrentAsyncInitializers = 1
            defaultAsyncDispatcher = parking
        }
        val tracer = RecordingStartupTracer()
        val initializer = AppStartupInitializer(context, tracer = tracer)
        val permitWait = StartupTraceNames.of("permitWait", PlainInit::class.java.name)

        runBlocking {
            initializer.coroutinesEngine.acquireAsyncPermit() // hold the only permit
            val job = launch(Dispatchers.Default) { initializer.doInitialize<String>(PlainInit::class.java) }
            awaitCondition { tracer.asyncBeginIndex(permitWait) >= 0 } // the waiter is requesting the permit
            delay(WAIT_FLOOR_MS) // guaranteed lower bound on the time spent waiting for the permit
            initializer.coroutinesEngine.releaseAsyncPermit()
            assertTrue(parking.dispatched.await(5, TimeUnit.SECONDS)) // permit acquired, body queued, never run
            job.cancel()
            parking.runParked() // a cancelled dispatched block completes without running the body
            job.join()
        }

        val metric = initializer.metricsFlow.replayCache.single()
        assertTrue(metric.wasCancelled)
        assertEquals(0L, metric.startTimestampMs)
        assertTrue("permit wait must be measured, got ${metric.permitWaitMs}", metric.permitWaitMs >= WAIT_FLOOR_MS - 5)
        assertTrue(
            "queueDelayMs=${metric.queueDelayMs} must cover permitWaitMs=${metric.permitWaitMs}",
            metric.permitWaitMs <= metric.queueDelayMs,
        )
    }

    @Test
    fun `permitWaitMs is negligible without a cap and never exceeds queueDelayMs`() = runTest {
        AppStartupInitializer.configure { defaultAsyncDispatcher = configDispatcher }
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(PlainInit::class.java)

        val metric = initializer.metricsFlow.replayCache.single()
        // No semaphore is configured, so the acquire is a no-op: only clock granularity remains.
        assertTrue("got ${metric.permitWaitMs}", metric.permitWaitMs < NEGLIGIBLE_MS)
        assertTrue(metric.permitWaitMs <= metric.queueDelayMs)
    }

    @Test
    fun `at cap of 1 the dependency completes before the dependent starts waiting for its permit`() = runTest {
        AppStartupInitializer.configure {
            maxConcurrentAsyncInitializers = 1
            defaultAsyncDispatcher = configDispatcher
        }
        val tracer = RecordingStartupTracer()
        val initializer = AppStartupInitializer(context, tracer = tracer)

        val result = initializer.doInitialize<String>(DependentInit::class.java)

        assertEquals("dependent(dependency)", result)
        val dependencyDone = tracer.asyncEndIndex(StartupTraceNames.of("create", DependencyInit::class.java.name))
        val dependentWait = tracer.asyncBeginIndex(StartupTraceNames.of("permitWait", DependentInit::class.java.name))
        assertTrue("dependency create() must have run", dependencyDone >= 0)
        assertTrue("dependent must request its permit", dependentWait >= 0)
        assertTrue(
            "dependency finished at $dependencyDone, dependent requested a permit at $dependentWait",
            dependencyDone < dependentWait,
        )
        assertEquals(2, initializer.metricsFlow.replayCache.size)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached in time" }
            delay(1)
        }
    }

    private fun runCapOneScenario(initializer: AppStartupInitializer, tracer: RecordingStartupTracer): List<StartupMetric> {
        stubManifest(HoldingInit::class.java, WaitingInit::class.java)
        initializer.discoverAndInitialize(InitializationProvider::class.java)
        assertTrue(firstRunning.await(5, TimeUnit.SECONDS))
        // Both initializers have requested a permit; only one can hold the single permit until the gate
        // opens, so the other one is parked on (or about to park on) the semaphore.
        runBlocking { awaitCondition { tracer.events.count { it is RecordingStartupTracer.Event.AsyncBegin && it.name.startsWith("Startup::permitWait::") } == 2 } }
        // Pure lower bound: the waiter requested its permit before this sleep started and cannot get it
        // before the gate opens, so its permit wait is at least this long regardless of scheduling.
        Thread.sleep(WAIT_FLOOR_MS)
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }
        return initializer.metricsFlow.replayCache.toList().also { assertEquals(2, it.size) }
    }

    private fun stubManifest(vararg classes: Class<*>) {
        val bundle = Bundle().apply { classes.forEach { putString(it.name, "async-initializer") } }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
    }

    companion object {
        val interceptors = java.util.concurrent.ConcurrentHashMap<Class<*>, CoroutineDispatcher?>()

        @Volatile
        var firstRunning = CountDownLatch(1)

        @Volatile
        var gate = CountDownLatch(1)

        private fun named(name: String): ExecutorCoroutineDispatcher =
            Executors.newFixedThreadPool(4) { r -> Thread(r, name).also { it.isDaemon = true } }
                .asCoroutineDispatcher()

        val configDispatcher = named("wiring-config")
        val overrideDispatcher = named("wiring-override")
        val roomyDispatcher = named("wiring-roomy")

        const val WAIT_FLOOR_MS = 40L
        const val NEGLIGIBLE_MS = 50L
    }

    /** Holds dispatched blocks until told to run them, to model "permit acquired, body not started". */
    class ParkingDispatcher : CoroutineDispatcher() {
        val dispatched = CountDownLatch(1)
        private val parked = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            parked.add(block)
            dispatched.countDown()
        }

        fun runParked() {
            generateSequence { parked.poll() }.forEach { it.run() }
        }
    }

    private class DependencyInit : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "dependency"
    }

    private class DependentInit : StartupAsyncInitializer<String> {
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> = listOf(DependencyInit::class.java)

        override suspend fun create(context: Context): String = "dependent(dependency)"
    }

    private open class Recording : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            interceptors[javaClass] = kotlin.coroutines.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            return "ok"
        }
    }

    private class PlainInit : Recording()
    private class OtherPlainInit : Recording()

    private class OverrideInit : Recording() {
        override fun dispatcher(): CoroutineDispatcher = overrideDispatcher
    }

    private class FailingInit : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = error("boom")
    }

    /** Whichever of the two acquires the single permit first signals and holds it until the gate opens. */
    private open class HoldingBase : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            firstRunning.countDown()
            gate.await(5, TimeUnit.SECONDS)
            return "held"
        }
    }

    private class HoldingInit : HoldingBase()
    private class WaitingInit : HoldingBase()
}

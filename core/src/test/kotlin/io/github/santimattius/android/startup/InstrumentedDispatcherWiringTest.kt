package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.dispatcher.InstrumentedDispatcher
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
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
        val initializer = AppStartupInitializer(context)

        val metrics = runCapOneScenario(initializer)

        val waiter = metrics.maxByOrNull { it.permitWaitMs }!!
        assertTrue("waiter must wait for the permit, got ${waiter.permitWaitMs}", waiter.permitWaitMs >= 30)
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
        val initializer = AppStartupInitializer(context)

        val metrics = runCapOneScenario(initializer)

        assertTrue(metrics.maxOf { it.permitWaitMs } >= 30)
        assertTrue(initializer.dispatcherSnapshots().isEmpty())
    }

    private fun runCapOneScenario(initializer: AppStartupInitializer): List<StartupMetric> {
        stubManifest(HoldingInit::class.java, WaitingInit::class.java)
        initializer.discoverAndInitialize(InitializationProvider::class.java)
        assertTrue(firstRunning.await(5, TimeUnit.SECONDS))
        Thread.sleep(50) // the other initializer is parked on the cap=1 permit meanwhile
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

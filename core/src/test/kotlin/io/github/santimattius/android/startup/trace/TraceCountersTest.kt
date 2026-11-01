package io.github.santimattius.android.startup.trace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.InitializationProvider
import io.github.santimattius.android.startup.StartupMetric
import io.github.santimattius.android.startup.StartupMetricsBus
import io.github.santimattius.android.startup.awaitAllStartJobs
import io.github.santimattius.android.startup.engine.AppStartupCoroutinesEngine
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

/** Spec: startup-async-tracing P2 counters (tasks 4.3-4.4): activeAsync, permit counters, busSize. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class TraceCountersTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val tracer = RecordingStartupTracer()

    @Before
    fun setUp() {
        rendezvous = CountDownLatch(2)
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        AppStartupInitializer.configure { }
    }

    private fun values(name: String) =
        tracer.events.filterIsInstance<RecordingStartupTracer.Event.Counter>().filter { it.name == name }.map { it.value }

    @Test
    fun `activeAsync never exceeds the cap reaches it and returns to zero`() {
        AppStartupInitializer.configure {
            maxConcurrentAsyncInitializers = 2
            defaultAsyncDispatcher = Dispatchers.IO
        }
        val bundle = Bundle().apply {
            listOf(Rv1::class.java, Rv2::class.java, Rv3::class.java).forEach { putString(it.name, "async-initializer") }
        }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runBlocking { initializer.awaitAllStartJobs() }

        val active = values("Startup::activeAsync")
        assertEquals(2L, active.max())
        assertTrue(active.all { it >= 0 })
        assertEquals(0L, active.last())
    }

    @Test
    fun `permit counters at cap 1 peak waiting at one and end with the permit available`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(Dispatchers.IO, cap = 1, tracer = tracer)
        engine.acquireAsyncPermit()
        val second = async(Dispatchers.IO) {
            engine.acquireAsyncPermit()
            engine.releaseAsyncPermit()
        }
        withTimeout(5_000) { while (1L !in values("Startup::permitsWaiting")) delay(5) }

        engine.releaseAsyncPermit()
        second.await()

        val waiting = values("Startup::permitsWaiting")
        assertEquals(1L, waiting.max())
        assertEquals(0L, waiting.last())
        val available = values("Startup::permitsAvailable")
        assertTrue("held permit shows 0 available", 0L in available)
        assertEquals(1L, available.max())
        assertEquals(1L, available.last())
    }

    @Test
    fun `busSize follows the bounded replay and never goes negative`() {
        val bus = StartupMetricsBus(capacity = 3, tracer = tracer)

        repeat(5) { bus.publish(StartupMetric("Init$it", durationMs = 1L, isAsync = false, success = true)) }

        assertEquals(listOf(1L, 2L, 3L, 3L, 3L), values("Startup::busSize"))
    }

    @Test
    fun `disabled tracer records no counters`() = runBlocking {
        tracer.enabled = false
        val engine = AppStartupCoroutinesEngine(Dispatchers.IO, cap = 1, tracer = tracer)
        val bus = StartupMetricsBus(capacity = 2, tracer = tracer)

        engine.acquireAsyncPermit()
        engine.enterActive()
        engine.exitActive()
        engine.releaseAsyncPermit()
        bus.publish(StartupMetric("Init", durationMs = 1L, isAsync = false, success = true))

        assertTrue(tracer.events.isEmpty())
    }

    companion object {
        @Volatile
        var rendezvous = CountDownLatch(2)

        /** Blocks until two initializers are inside create() at once, proving real concurrency of 2. */
        private fun meet() {
            rendezvous.countDown()
            rendezvous.await(5, TimeUnit.SECONDS)
        }
    }

    class Rv1 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "rv1".also { meet() }
    }

    class Rv2 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "rv2".also { meet() }
    }

    class Rv3 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "rv3".also { meet() }
    }
}

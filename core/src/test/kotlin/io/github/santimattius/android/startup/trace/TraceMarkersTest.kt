package io.github.santimattius.android.startup.trace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.InitializationProvider
import io.github.santimattius.android.startup.StartupPriority
import io.github.santimattius.android.startup.awaitAllStartJobs
import io.github.santimattius.android.startup.engine.AppStartupCoroutinesEngine
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec: startup-async-tracing P3 markers (tasks 4.7-4.8): transitions, cancel, warnings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class TraceMarkersTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val tracer = RecordingStartupTracer()

    @Before
    fun setUp() {
        allEntered = CountDownLatch(3)
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

    private fun markers() =
        tracer.events.filterIsInstance<RecordingStartupTracer.Event.Marker>().map { it.name }

    @Test
    fun `IN_PROGRESS then COMPLETED markers on a successful startup`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(Dispatchers.Default, tracer = tracer)
        engine.launchStartJob { }
        engine.launchStartJob { }
        engine.awaitAllStartJobs()

        assertEquals(listOf("Startup::marker::IN_PROGRESS", "Startup::marker::COMPLETED"), markers())
    }

    @Test
    fun `IN_PROGRESS then ERROR markers when a start job fails`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(Dispatchers.Default, tracer = tracer)
        engine.launchStartJob<Unit> { error("boom") }
        val thrown = runCatching { engine.awaitAllStartJobs() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(listOf("Startup::marker::IN_PROGRESS", "Startup::marker::ERROR"), markers())
    }

    @Test
    fun `timeout emits a cancel marker and still rethrows the CancellationException`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(Dispatchers.Default, tracer = tracer)
        val never = CompletableDeferred<Unit>()
        engine.launchStartJob { never.await() }

        val thrown = runCatching { engine.awaitAllStartJobs(50) }.exceptionOrNull()

        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        assertTrue("Startup::marker::cancel" in markers())
        assertFalse("Startup::marker::ERROR" in markers())
        engine.cancel()
    }

    @Test
    fun `external cancellation of the awaiter emits a cancel marker and is rethrown`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(Dispatchers.Default, tracer = tracer)
        val never = CompletableDeferred<Unit>()
        engine.launchStartJob { never.await() }

        val awaiter = async { engine.awaitAllStartJobs() }
        val observed = CompletableDeferred<Throwable?>()
        launch { observed.complete(runCatching { awaiter.await() }.exceptionOrNull()) }
        kotlinx.coroutines.yield()
        awaiter.cancel()

        assertTrue(observed.await() is CancellationException)
        assertTrue("Startup::marker::cancel" in markers())
        engine.cancel()
    }

    @Test
    fun `engine cancel emits a cancel marker`() {
        val engine = AppStartupCoroutinesEngine(Dispatchers.Default, tracer = tracer)
        engine.cancel()
        assertEquals(listOf("Startup::marker::cancel"), markers())
    }

    @Test
    fun `priority inversion marker is emitted once with the FQN`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)
        val discovered = setOf(NormalDependsOnDeferred::class.java, DeferredDep::class.java)

        initializer.computeEffectivePriorities(discovered)
        initializer.computeEffectivePriorities(discovered)

        assertEquals(
            listOf(StartupTraceNames.of("marker::priorityInversion", DeferredDep::class.java.name)),
            markers(),
        )
    }

    @Test
    fun `strict mode marker is emitted once when concurrency exceeds the threshold`() {
        AppStartupInitializer.configure {
            strictModeCheckEnabled = true
            strictModeConcurrencyThreshold = 1
            defaultAsyncDispatcher = Dispatchers.IO
        }
        val bundle = Bundle().apply {
            listOf(Warn1::class.java, Warn2::class.java, Warn3::class.java).forEach { putString(it.name, "async-initializer") }
        }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        allEntered.await(5, TimeUnit.SECONDS)
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }

        assertEquals(1, markers().count { it == "Startup::marker::strictModeConcurrency" })
    }

    @Test
    fun `no marker and no name evaluation when tracing is disabled`() = runBlocking {
        tracer.enabled = false
        val engine = AppStartupCoroutinesEngine(Dispatchers.Default, tracer = tracer)
        engine.launchStartJob { }
        engine.awaitAllStartJobs()
        engine.cancel()
        AppStartupInitializer(context, Dispatchers.Default, tracer).computeEffectivePriorities(
            setOf(NormalDependsOnDeferred::class.java, DeferredDep::class.java),
        )

        assertTrue(tracer.events.isEmpty())
        var evaluated = false
        tracer.mark { evaluated = true; "x" }
        assertFalse(evaluated)
    }

    // ── inline fakes ─────────────────────────────────────────────────────────

    class DeferredDep : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "deferred"
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
    }

    class NormalDependsOnDeferred : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "normal"
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> = listOf(DeferredDep::class.java)
    }

    open class Barrier : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            allEntered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            return "ok"
        }
    }

    class Warn1 : Barrier()
    class Warn2 : Barrier()
    class Warn3 : Barrier()

    companion object {
        @Volatile
        var allEntered = CountDownLatch(3)

        @Volatile
        var gate = CountDownLatch(1)
    }
}

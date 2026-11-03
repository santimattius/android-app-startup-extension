package io.github.santimattius.android.startup.trace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.AsyncInitializerStrategy
import io.github.santimattius.android.startup.FirstFrameSignal
import io.github.santimattius.android.startup.InitializationProvider
import io.github.santimattius.android.startup.InitializerRegistry
import io.github.santimattius.android.startup.StartupPriority
import io.github.santimattius.android.startup.SyncOrderingStrategy
import io.github.santimattius.android.startup.awaitAllStartJobs
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec: startup-async-tracing P1 catalog (tasks 4.1-4.2): discover, resolveGraph, instantiate, deferred. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class TraceCatalogTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val tracer = RecordingStartupTracer()

    @Before
    fun setUp() {
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        AppStartupInitializer.configure { }
    }

    @Test
    fun `discover and resolveGraph are sync slices on the calling thread and close before any permit`() {
        AppStartupInitializer.configure {
            syncOrderingStrategy = SyncOrderingStrategy.Topological
            asyncInitializerStrategy = AsyncInitializerStrategy.Validated
            maxConcurrentAsyncInitializers = 1
        }
        stubManifest(sync = listOf(SyncA::class.java, SyncB::class.java), async = listOf(AsyncRoot::class.java))
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)
        val caller = Thread.currentThread().name

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runBlocking { initializer.awaitAllStartJobs() }

        val phases = listOf(
            "Startup::discover::sync", "Startup::resolveGraph::topological", "Startup::discover::async",
            "Startup::resolveGraph::validateRoots", "Startup::resolveGraph::effectivePriorities",
        )
        phases.forEach { name ->
            val begin = tracer.events.filterIsInstance<RecordingStartupTracer.Event.Begin>().firstOrNull { it.name == name }
            assertEquals("$name must begin on the calling thread", caller, begin?.thread)
            assertTrue("$name must end", tracer.syncEndIndex(name) > tracer.syncBeginIndex(name))
        }
        // discover closes before its resolveGraph begins, per phase.
        assertTrue(tracer.syncEndIndex("Startup::discover::sync") < tracer.syncBeginIndex("Startup::resolveGraph::topological"))
        assertTrue(tracer.syncEndIndex("Startup::discover::async") < tracer.syncBeginIndex("Startup::resolveGraph::validateRoots"))
        // I1: the whole resolution closes before the first permit request.
        val firstPermit = tracer.asyncBeginIndex("Startup::permitWait::${AsyncRoot::class.java.name}")
        assertTrue(firstPermit > tracer.syncEndIndex("Startup::resolveGraph::effectivePriorities"))
        assertTrue(firstPermit > tracer.syncEndIndex("Startup::resolveGraph::validateRoots"))
        assertTrue(tracer.syncBalanced())
    }

    @Test
    fun `registry emits one instantiate slice per class and ends it when the constructor throws`() {
        val registry = InitializerRegistry(tracer)

        registry.peek(SyncA::class.java)
        registry.take(SyncA::class.java) // reuses the peeked instance: no second construction
        registry.peek(SyncB::class.java)
        runCatching { registry.peek(ThrowingCtor::class.java) }

        fun begins(cls: Class<*>) = tracer.events.count {
            it is RecordingStartupTracer.Event.Begin && it.name == "Startup::instantiate::${cls.name}"
        }
        assertEquals(1, begins(SyncA::class.java))
        assertEquals(1, begins(SyncB::class.java))
        assertEquals(1, begins(ThrowingCtor::class.java))
        assertTrue(tracer.syncEndIndex("Startup::instantiate::${ThrowingCtor::class.java.name}") >= 0)
        assertTrue(tracer.syncBalanced())
    }

    @Test
    fun `deferred awaitFirstFrame slice closes on the signal with a signal marker and no timeout marker`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val signal = FakeSignal()
        AppStartupInitializer.configure { firstFrameSignal = signal; defaultAsyncDispatcher = dispatcher }
        val initializer = AppStartupInitializer(context, dispatcher, tracer)
        stubManifest(async = listOf(DeferredInit::class.java))
        val slice = "Startup::deferred::awaitFirstFrame::${DeferredInit::class.java.name}"

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCurrent()
        assertTrue("slice must be open while waiting", tracer.asyncBeginIndex(slice) >= 0)
        assertEquals(-1, tracer.asyncEndIndex(slice))

        signal.fire()
        runCurrent()

        val marker = tracer.indexOf { it is RecordingStartupTracer.Event.Marker && it.name == markerName("deferredSignal") }
        assertTrue(tracer.asyncEndIndex(slice) in 0 until marker)
        assertEquals(-1, tracer.indexOf { it.name == markerName("deferredTimeout") })
        assertTrue(tracer.asyncBeginIndex("Startup::create::${DeferredInit::class.java.name}") > marker)
        assertTrue(tracer.asyncBalanced())
        initializer.coroutinesEngine.cancel()
    }

    @Test
    fun `deferred awaitFirstFrame slice closes on timeout with a timeout marker and the initializer still runs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        AppStartupInitializer.configure {
            firstFrameSignal = FakeSignal() // never fires
            defaultAsyncDispatcher = dispatcher
            deferredStartupTimeoutMs = 1_000L
        }
        val initializer = AppStartupInitializer(context, dispatcher, tracer)
        stubManifest(async = listOf(DeferredInit::class.java))
        val slice = "Startup::deferred::awaitFirstFrame::${DeferredInit::class.java.name}"

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCurrent()
        assertEquals(-1, tracer.asyncEndIndex(slice))
        advanceTimeBy(1_001L)
        runCurrent()

        val marker = tracer.indexOf { it is RecordingStartupTracer.Event.Marker && it.name == markerName("deferredTimeout") }
        assertTrue(tracer.asyncEndIndex(slice) in 0 until marker)
        assertEquals(-1, tracer.indexOf { it.name == markerName("deferredSignal") })
        assertTrue(tracer.asyncEndIndex("Startup::create::${DeferredInit::class.java.name}") > marker)
        assertTrue(tracer.asyncBalanced())
        initializer.coroutinesEngine.cancel()
    }

    @Test
    fun `disabled tracer records nothing for discover resolveGraph and deferred paths`() = runTest {
        tracer.enabled = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        AppStartupInitializer.configure {
            syncOrderingStrategy = SyncOrderingStrategy.Topological
            asyncInitializerStrategy = AsyncInitializerStrategy.Validated
            firstFrameSignal = FakeSignal().also { it.fire() }
            defaultAsyncDispatcher = dispatcher
        }
        stubManifest(sync = listOf(SyncA::class.java), async = listOf(DeferredInit::class.java))
        val initializer = AppStartupInitializer(context, dispatcher, tracer)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCurrent()

        assertTrue(tracer.events.isEmpty())
        initializer.coroutinesEngine.cancel()
    }

    private fun markerName(kind: String) = "Startup::marker::$kind::${DeferredInit::class.java.name}"

    private fun stubManifest(sync: List<Class<*>> = emptyList(), async: List<Class<*>> = emptyList()) {
        val bundle = Bundle().apply {
            sync.forEach { putString(it.name, "sync-initializer") }
            async.forEach { putString(it.name, "async-initializer") }
        }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
    }

    private class FakeSignal : FirstFrameSignal {
        private val frame = CompletableDeferred<Unit>()
        override suspend fun await() = frame.await()
        fun fire() {
            frame.complete(Unit)
        }
    }

    class SyncA : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"
    }

    class SyncB : StartupSyncInitializer<String> {
        override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = listOf(SyncA::class.java)
        override fun create(context: Context) = "b"
    }

    class AsyncRoot : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "root"
    }

    class DeferredInit : StartupAsyncInitializer<String> {
        override fun priority() = StartupPriority.DEFERRED
        override suspend fun create(context: Context) = "deferred"
    }

    class ThrowingCtor : StartupSyncInitializer<String> {
        init {
            error("boom")
        }

        override fun create(context: Context) = "never"
    }
}

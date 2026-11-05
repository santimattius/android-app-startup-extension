package com.santimattius.android.sample.usecases

import android.app.Application
import android.os.Build
import com.santimattius.android.sample.AsyncTestInitializer
import com.santimattius.android.sample.DependencyOnlyInitializer
import com.santimattius.android.sample.MainApplication
import com.santimattius.android.sample.SyncTestInitializer
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.InitializationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs the cases against the real library singleton. The provider is not started, so only the
 * on-demand paths are exercised; the singleton outlives a test, hence deltas instead of totals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], application = Application::class)
class OnDemandCasesTest {
    private val startup get() = AppStartupInitializer.getInstance(RuntimeEnvironment.getApplication())

    private fun run(id: String) = runBlocking { OnDemandCases.all.first { it.id == id }.run(startup) }

    @Test
    fun `retained sync initializer is created at most once and cached`() {
        val before = DemoCounters.count(RetainedSyncDemo::class.java)
        val first = startup.doInitialize<String>(RetainedSyncDemo::class.java)
        val second = startup.initializeComponent(RetainedSyncDemo::class.java)
        assertSame(first, second)
        assertTrue(DemoCounters.count(RetainedSyncDemo::class.java) - before <= 1)
    }

    @Test
    fun `transient initializers run create again on every request`() {
        val sync = DemoCounters.count(TransientSyncDemo::class.java)
        val async = DemoCounters.count(TransientAsyncDemo::class.java)
        run("transient")
        assertEquals(2, DemoCounters.count(TransientSyncDemo::class.java) - sync)
        assertEquals(2, DemoCounters.count(TransientAsyncDemo::class.java) - async)
    }

    @Test
    fun `dependencies are created before their dependents`() {
        val lines = run("deps")
        assertEquals("sync -> sync: [SyncChildDemo, SyncParentDemo]", lines[0])
        assertEquals("async -> async: [AsyncChildDemo, AsyncParentDemo]", lines[1])
        // The async dependent needs the (transient) sync dependency, so it is created first again.
        assertEquals("async -> sync: [SyncChildDemo, AsyncNeedsSyncDemo]", lines[2])
    }

    @Test
    fun `on-demand and dependency classes are never eager`() {
        val lines = run("eager")
        assertEquals("DependencyOnlyInitializer (dependency only): false", lines[1])
        assertEquals("RetainedSyncDemo (on demand): false", lines[2])
        assertEquals("RetainedAsyncDemo (on demand): false", lines[4])
    }

    @Test
    fun `shared demo runner rejects concurrent requests`() =
        runTest {
            val runner = DemoCaseRunner.shared
            var active = 0
            var peak = 0
            val case =
                DemoCase("serial", "serial", "serial") {
                    active += 1
                    peak = maxOf(peak, active)
                    delay(10)
                    active -= 1
                    emptyList()
                }

            val first = async { runner.run(case, startup) }
            testScheduler.runCurrent()
            val rejected = runner.run(case, startup)
            first.await()

            assertNull(rejected)
            assertEquals(1, peak)
            assertNull(runner.runningCaseId.value)
        }

    @Test
    fun `demo runner preserves coroutine cancellation and clears running state`() =
        runTest {
            val runner = DemoCaseRunner.shared
            val case = DemoCase("cancel", "cancel", "cancel") { throw CancellationException("left screen") }
            val result = runCatching { runner.run(case, startup) }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertNull(runner.runningCaseId.value)
        }

    @Test
    fun `every basic case runs without throwing and produces output`() {
        OnDemandCases.basic.forEach { case ->
            val lines = runBlocking { case.run(startup) }
            assertTrue("${case.id} produced no output", lines.isNotEmpty())
            assertTrue("${case.id}: $lines", lines.none { it.startsWith("threw") })
        }
    }

    @Test
    fun `every case has a unique id`() {
        assertEquals(
            OnDemandCases.all.size,
            OnDemandCases.all
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `dispatcher override is visible in the metric`() {
        val lines = run("dispatcher")
        assertTrue(lines[0], lines[0].contains("DefaultDispatcher-worker") || lines[0].contains("Dispatchers.IO"))
        assertTrue(lines[1], lines[1].contains("on demo-single"))
    }

    @Test
    fun `deferred priority is reported but not gated on demand`() {
        val lines = run("priority")
        assertTrue(lines[0].contains("priority=CRITICAL"))
        assertTrue(lines[1].contains("priority=DEFERRED"))
    }

    @Test
    fun `failure is reported and a later serialized request still succeeds`() {
        val lines = run("failure")
        assertTrue(lines[0], lines[0].startsWith("failing threw: StartupExtensionException"))
        assertTrue(lines[1], lines[1].contains("success=false") && lines[1].contains("demo failure"))
        assertTrue(lines[2], lines[2].startsWith("next serialized request -> transient-async#"))
    }

    @Test
    fun `abstract classes and cycles raise StartupExtensionException`() {
        val lines = run("exceptions")
        assertTrue(lines[0], lines[0].startsWith("abstract sync: StartupExtensionException"))
        assertTrue(lines[1], lines[1].contains("Cycle detected"))
        assertTrue(lines[2], lines[2].startsWith("abstract async: StartupExtensionException"))
        assertTrue(lines[3], lines[3].contains("Cycle detected"))
    }

    @Test
    fun `dispatcher instrumentation produces snapshots and restores captured settings`() {
        val previous = SampleStartup.current
        val baseline = StartupSettings(debugLogging = true, deferredStartupTimeoutMs = 1_234)
        try {
            SampleStartup.apply(baseline)
            val lines = runBlocking { DispatcherCases.all.single().run(startup) }

            assertTrue(lines.toString(), lines.any { it.contains("dispatched=") })
            assertTrue(startup.dispatcherSnapshots().values.sumOf { it.totalDispatched } >= 9)
            assertEquals(baseline, SampleStartup.current)
            assertEquals("previous settings restored", lines.last())
        } finally {
            SampleStartup.apply(previous)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], application = MainApplication::class)
class EagerInitializerFlagsTest {
    @Test
    fun `manifest initializers are reported as eager`() {
        Robolectric.buildContentProvider(InitializationProvider::class.java).create().get()
        val startup = AppStartupInitializer.getInstance(RuntimeEnvironment.getApplication())
        val lines = runBlocking { OnDemandCases.basic.first { it.id == "eager" }.run(startup) }

        assertTrue(startup.isEagerlySyncInitialized(SyncTestInitializer::class.java))
        assertTrue(startup.isEagerlyAsyncInitialized(AsyncTestInitializer::class.java))
        assertFalse(startup.isEagerlySyncInitialized(DependencyOnlyInitializer::class.java))
        assertEquals("SyncTestInitializer (manifest): true", lines[0])
        assertEquals("AsyncTestInitializer (manifest): true", lines[3])
    }
}

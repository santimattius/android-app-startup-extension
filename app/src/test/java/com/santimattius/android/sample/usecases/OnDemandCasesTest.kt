package com.santimattius.android.sample.usecases

import android.app.Application
import android.os.Build
import io.github.santimattius.android.startup.AppStartupInitializer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
    fun `on-demand classes are never eager`() {
        assertFalse(startup.isEagerlySyncInitialized(RetainedSyncDemo::class.java))
        assertFalse(startup.isEagerlyAsyncInitialized(RetainedAsyncDemo::class.java))
        assertTrue(run("eager").last().endsWith("false"))
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
        assertEquals(OnDemandCases.all.size, OnDemandCases.all.map { it.id }.toSet().size)
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
    fun `failure is isolated from its sibling`() {
        val lines = run("failure")
        assertTrue(lines[0], lines[0].startsWith("failing threw: StartupExtensionException"))
        assertTrue(lines[1], lines[1].contains("success=false") && lines[1].contains("demo failure"))
        assertTrue(lines[2], lines[2].startsWith("sibling -> transient-async#"))
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
    fun `dispatcher instrumentation produces snapshots and can be restored`() {
        try {
            val lines = runBlocking {
                DispatcherCases.all.take(2).flatMap { it.run(startup) }
            }
            assertTrue(lines.toString(), lines.any { it.contains("dispatched=") })
            assertTrue(startup.dispatcherSnapshots().values.sumOf { it.totalDispatched } >= 9)
        } finally {
            SampleStartup.apply(StartupSettings())
        }
        assertFalse(SampleStartup.current.dispatcherInstrumentation)
    }
}

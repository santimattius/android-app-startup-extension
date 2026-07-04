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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Spec: startup-priority-staggering — activation wiring (task 5.1/5.2).
 *
 * Exercises the REAL discovery→launch path (`discoverAndInitialize` → `asyncDiscoverAndInitialize`
 * → partition by effective priority → `launchStartJob` / `launchDeferredStartJob` → private
 * `doAsyncInitialize`). This is the only path that partitions roots and gates DEFERRED work behind
 * the first-frame signal; the public on-demand `doInitialize` serializes through a mutex and never
 * partitions.
 *
 * All scenarios run on virtual time: the engine dispatcher AND `defaultAsyncDispatcher` are a single
 * [StandardTestDispatcher] bound to `runTest`'s scheduler, so `runCurrent()`/`advanceTimeBy()` fully
 * control eager execution, the injected [FakeFirstFrameSignal], and the `withTimeoutOrNull` gate.
 *
 * Per the design's testability notes: use `runCurrent()` (NOT `advanceUntilIdle()`) for
 * "not-yet-run" assertions so the deferred timeout does not fire prematurely, and `engine.cancel()`
 * at the end of each test releases any coroutine still suspended on an unresolved signal so
 * `runTest` can drain without an `UncompletedCoroutinesError`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class DeferredStartupGatingTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        created.clear()
        // Captured at AppStartupInitializer construction — must be stubbed before constructing.
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        AppStartupInitializer.configure { }
    }

    // ── Deferred waits for the frame; injected fake drives scheduling; fire then launch ──

    @Test
    fun `DEFERRED root does not run before the first frame and runs after the injected fake fires`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeFirstFrameSignal()
        AppStartupInitializer.configure {
            firstFrameSignal = fake
            defaultAsyncDispatcher = dispatcher
        }
        val initializer = AppStartupInitializer(context, dispatcher)
        stubManifest(EagerNormalInit::class.java, DeferredInit::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCurrent()

        assertTrue("The eager NORMAL root must run immediately", created.contains("EagerNormalInit"))
        assertFalse(
            "The DEFERRED root must NOT run before the first-frame signal resolves",
            created.contains("DeferredInit"),
        )

        fake.fire()
        runCurrent()

        assertTrue(
            "The DEFERRED root's create() must run exactly once the injected signal fires",
            created.contains("DeferredInit"),
        )

        initializer.coroutinesEngine.cancel()
    }

    // ── awaitAllStartJobs excludes DEFERRED ───────────────────────────────────

    @Test
    fun `awaitAllStartJobs returns without waiting on a DEFERRED root suspended on the signal`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeFirstFrameSignal() // never fired
        AppStartupInitializer.configure {
            firstFrameSignal = fake
            defaultAsyncDispatcher = dispatcher
        }
        val initializer = AppStartupInitializer(context, dispatcher)
        stubManifest(EagerNormalInit::class.java, DeferredInit::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        initializer.awaitAllStartJobs()

        assertTrue("The eager root must have completed", created.contains("EagerNormalInit"))
        assertFalse(
            "awaitAllStartJobs must return before the DEFERRED root runs",
            created.contains("DeferredInit"),
        )
        assertTrue(
            "The DEFERRED job must still be active (excluded from the critical-path await)",
            initializer.coroutinesEngine.deferredStartJobs.single().isActive,
        )

        initializer.coroutinesEngine.cancel()
    }

    // ── Headless flush via timeout (no frame ever drawn) ──────────────────────

    @Test
    fun `a never-fired signal flushes the DEFERRED root once the timeout elapses`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeFirstFrameSignal() // never fired
        AppStartupInitializer.configure {
            firstFrameSignal = fake
            defaultAsyncDispatcher = dispatcher
            deferredStartupTimeoutMs = 1_000L
        }
        val initializer = AppStartupInitializer(context, dispatcher)
        stubManifest(DeferredInit::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCurrent()
        assertFalse(
            "The DEFERRED root must not run before the timeout elapses",
            created.contains("DeferredInit"),
        )

        advanceTimeBy(1_001L)
        runCurrent()

        assertTrue(
            "The headless timeout must flush the DEFERRED root even though no frame is drawn",
            created.contains("DeferredInit"),
        )

        initializer.coroutinesEngine.cancel()
    }

    // ── CRITICAL == NORMAL in v1: both eager, neither gated ───────────────────

    @Test
    fun `CRITICAL and NORMAL roots both launch eagerly and neither is deferred`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeFirstFrameSignal() // never fired
        AppStartupInitializer.configure {
            firstFrameSignal = fake
            defaultAsyncDispatcher = dispatcher
        }
        val initializer = AppStartupInitializer(context, dispatcher)
        stubManifest(EagerCriticalInit::class.java, EagerNormalInit::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCurrent()

        assertTrue("CRITICAL behaves like NORMAL (eager) in v1", created.contains("EagerCriticalInit"))
        assertTrue("NORMAL is eager", created.contains("EagerNormalInit"))
        assertTrue(
            "No DEFERRED roots => nothing is gated behind the frame",
            initializer.coroutinesEngine.deferredStartJobs.isEmpty(),
        )

        initializer.coroutinesEngine.cancel()
    }

    // ── No override == pre-change baseline (eager, immediate) ─────────────────

    @Test
    fun `an initializer with no priority override runs eagerly like the pre-change baseline`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeFirstFrameSignal() // never fired
        AppStartupInitializer.configure {
            firstFrameSignal = fake
            defaultAsyncDispatcher = dispatcher
        }
        val initializer = AppStartupInitializer(context, dispatcher)
        stubManifest(EagerNormalInit::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runCurrent()

        assertTrue(
            "A no-override initializer must run immediately on the eager critical path",
            created.contains("EagerNormalInit"),
        )
        assertTrue(
            "A no-override graph must launch no deferred jobs at all",
            initializer.coroutinesEngine.deferredStartJobs.isEmpty(),
        )

        initializer.coroutinesEngine.cancel()
    }

    // ── cap=1 + mixed priorities + dependency chain: no deadlock (Validated roots) ──

    @Test
    fun `cap of 1 with mixed priorities and a dependency chain completes without deadlock`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = FakeFirstFrameSignal()
        AppStartupInitializer.configure {
            firstFrameSignal = fake
            defaultAsyncDispatcher = dispatcher
            maxConcurrentAsyncInitializers = 1
            asyncInitializerStrategy = AsyncInitializerStrategy.Validated
        }
        val initializer = AppStartupInitializer(context, dispatcher)
        stubManifest(ChainParent::class.java, ChainChild::class.java, CapDeferred::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)

        fake.fire()
        advanceUntilIdle()

        assertTrue(
            "The eager child must resolve before the parent acquires the single permit (no deadlock)",
            created.contains("ChainChild"),
        )
        assertTrue("The eager parent must complete under cap=1", created.contains("ChainParent"))
        assertTrue(
            "The DEFERRED root must flush after the frame under cap=1 (permit never nested)",
            created.contains("CapDeferred"),
        )

        initializer.coroutinesEngine.cancel()
    }

    // ── Manifest stubbing helper (mirrors AsyncConcurrencyGatingTest) ─────────

    private fun stubManifest(vararg classes: Class<*>) {
        val bundle = Bundle().apply {
            classes.forEach { putString(it.name, "async-initializer") }
        }
        val providerInfo = ProviderInfo().apply { metaData = bundle }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns providerInfo
    }

    // ── Inline fakes (no shared fixtures) ─────────────────────────────────────

    companion object {
        /** Records the simpleName of every initializer whose create() actually executed. */
        val created: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    /** Fake first-frame signal driven by the test — fire() completes the underlying deferred. */
    private class FakeFirstFrameSignal : FirstFrameSignal {
        private val frame = CompletableDeferred<Unit>()
        override suspend fun await() {
            frame.await()
        }

        fun fire() {
            frame.complete(Unit)
        }
    }

    private class EagerNormalInit : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            created += "EagerNormalInit"
            return "normal"
        }
    }

    private class EagerCriticalInit : StartupAsyncInitializer<String> {
        override fun priority(): StartupPriority = StartupPriority.CRITICAL
        override suspend fun create(context: Context): String {
            created += "EagerCriticalInit"
            return "critical"
        }
    }

    private class DeferredInit : StartupAsyncInitializer<String> {
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
        override suspend fun create(context: Context): String {
            created += "DeferredInit"
            return "deferred"
        }
    }

    private class ChainChild : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            created += "ChainChild"
            return "child"
        }
    }

    private class ChainParent : StartupAsyncInitializer<String> {
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(ChainChild::class.java)

        override suspend fun create(context: Context): String {
            created += "ChainParent"
            return "parent"
        }
    }

    private class CapDeferred : StartupAsyncInitializer<String> {
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
        override suspend fun create(context: Context): String {
            created += "CapDeferred"
            return "capDeferred"
        }
    }
}

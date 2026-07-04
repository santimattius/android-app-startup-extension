package io.github.santimattius.android.startup.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2 (active-concurrency counter) + Phase 4 (Semaphore gating) of the
 * Coroutine Storm Mitigation change. These primitives are additive/inert in
 * this PR — nothing in [io.github.santimattius.android.startup.AppStartupInitializer]
 * calls [AppStartupCoroutinesEngine.acquireAsyncPermit]/[AppStartupCoroutinesEngine.releaseAsyncPermit]
 * from the hot path yet (that wiring is a separate follow-up change).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupCoroutinesEngineTest {

    // ── Phase 2: enterActive() / exitActive() ────────────────────────────────

    @Test
    fun `enterActive increments and exitActive decrements the active counter`() {
        val engine = AppStartupCoroutinesEngine()

        val first = engine.enterActive()
        val second = engine.enterActive()
        engine.exitActive()
        val afterOneExit = engine.enterActive()

        assertEquals("first enter must start at 1", 1, first)
        assertEquals("second concurrent enter must be 2", 2, second)
        assertEquals("after one exit (2->1), re-entering must be 2 again", 2, afterOneExit)
    }

    @Test
    fun `enterActive snapshot is never stale under parallel calls`() = runBlocking {
        val engine = AppStartupCoroutinesEngine()
        val concurrency = 20
        val peak = AtomicInteger(0)
        val readyLatch = CountDownLatch(concurrency)
        val releaseLatch = CountDownLatch(1)

        // Dispatchers.IO (not Default) — CountDownLatch.await() below is a REAL blocking
        // call, and Dispatchers.Default's parallelism is capped at the CPU core count.
        // With concurrency=20 on a machine with fewer cores, Default would starve and
        // never reach a true peak of 20; IO's much larger pool avoids that false negative.
        val jobs = (1..concurrency).map {
            async(Dispatchers.IO) {
                val snapshot = engine.enterActive()
                peak.updateAndGet { current -> maxOf(current, snapshot) }
                readyLatch.countDown()
                releaseLatch.await(5, TimeUnit.SECONDS)
                engine.exitActive()
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        releaseLatch.countDown()
        jobs.awaitAll()

        assertEquals(
            "Peak concurrent snapshot must equal the number of launched coroutines",
            concurrency,
            peak.get(),
        )
        assertEquals(
            "Counter must return to zero after every coroutine exits",
            1,
            engine.enterActive(), // 0 (drained) + 1 for this call
        )
    }

    // ── Phase 4: acquireAsyncPermit() / releaseAsyncPermit() ─────────────────

    @Test
    fun `cap null - acquireAsyncPermit is a no-op, concurrency stays unbounded`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(cap = null)
        val concurrency = 5
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val readyLatch = CountDownLatch(concurrency)
        val releaseLatch = CountDownLatch(1)

        // Dispatchers.IO for the same reason as above — a blocking CountDownLatch.await()
        // needs a pool larger than the CPU core count to reach true concurrency=5 reliably.
        val jobs = (1..concurrency).map {
            async(Dispatchers.IO) {
                engine.acquireAsyncPermit()
                try {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { max -> maxOf(max, current) }
                    readyLatch.countDown()
                    releaseLatch.await(5, TimeUnit.SECONDS)
                    active.decrementAndGet()
                } finally {
                    engine.releaseAsyncPermit()
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        releaseLatch.countDown()
        jobs.awaitAll()

        assertEquals(
            "cap=null must preserve current unbounded behavior",
            concurrency,
            peak.get(),
        )
    }

    @Test
    fun `cap 2 - acquireAsyncPermit bounds peak concurrent executions to the cap`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(cap = 2)
        val concurrency = 5
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val completed = AtomicInteger(0)

        val jobs = (1..concurrency).map {
            async(Dispatchers.Default) {
                engine.acquireAsyncPermit()
                try {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { max -> maxOf(max, current) }
                    delay(50)
                    active.decrementAndGet()
                    completed.incrementAndGet()
                } finally {
                    engine.releaseAsyncPermit()
                }
            }
        }

        jobs.awaitAll()

        assertTrue(
            "Peak concurrency (${peak.get()}) must never exceed the configured cap (2)",
            peak.get() <= 2,
        )
        assertEquals(
            "cap=2 must still let all 5 blocking fakes complete — none dropped or lost",
            concurrency,
            completed.get(),
        )
    }

    @Test
    fun `cap 1 - dependency resolved fully before parent acquires never deadlocks`() = runBlocking {
        val engine = AppStartupCoroutinesEngine(cap = 1)
        val order = mutableListOf<String>()

        withTimeout(5_000) {
            suspend fun runChild(name: String) {
                engine.acquireAsyncPermit()
                try {
                    order += name
                } finally {
                    engine.releaseAsyncPermit()
                }
            }

            // Mirrors AppStartupInitializer's ordering rule: the dependency's
            // permit is fully acquired AND released before the parent acquires
            // its own — never nested — which is what keeps cap=1 deadlock-free.
            suspend fun runParent(name: String, dependency: String) {
                runChild(dependency)
                engine.acquireAsyncPermit()
                try {
                    order += name
                } finally {
                    engine.releaseAsyncPermit()
                }
            }

            runParent("parent", "child")
        }

        assertEquals(listOf("child", "parent"), order)
    }

    // ── startup-priority-staggering: deferred start jobs (ADR-4) ─────────────

    @Test
    fun `launchDeferredStartJob tracks the job in deferredStartJobs and not startJobs`() = runTest {
        val engine = AppStartupCoroutinesEngine(StandardTestDispatcher(testScheduler))

        engine.launchDeferredStartJob { /* no-op */ }

        assertEquals(
            "A deferred job must be tracked in the separate deferredStartJobs list",
            1,
            engine.deferredStartJobs.size,
        )
        assertTrue(
            "A deferred job must NOT be added to the critical-path startJobs list",
            engine.startJobs.isEmpty(),
        )
    }

    @Test
    fun `awaitAllStartJobs returns without awaiting a deferred job suspended on an unresolved signal`() = runTest {
        val engine = AppStartupCoroutinesEngine(StandardTestDispatcher(testScheduler))
        val signal = CompletableDeferred<Unit>()
        var eagerRan = false
        var deferredRan = false

        engine.launchStartJob { eagerRan = true }
        engine.launchDeferredStartJob {
            signal.await() // never resolved in this test
            deferredRan = true
        }

        engine.awaitAllStartJobs()

        assertTrue("The eager critical-path job must have run", eagerRan)
        assertFalse(
            "The deferred job must NOT run while its signal is unresolved",
            deferredRan,
        )
        assertTrue(
            "awaitAllStartJobs must return while the deferred job is still active (excluded from the await)",
            engine.deferredStartJobs.single().isActive,
        )

        engine.cancel() // release the suspended deferred coroutine so runTest can drain
    }

    @Test
    fun `deferred job runs once its signal resolves`() = runTest {
        val engine = AppStartupCoroutinesEngine(StandardTestDispatcher(testScheduler))
        val signal = CompletableDeferred<Unit>()
        var deferredRan = false

        engine.launchDeferredStartJob {
            signal.await()
            deferredRan = true
        }

        runCurrent()
        assertFalse("Deferred work must not run before the signal resolves", deferredRan)

        signal.complete(Unit)
        runCurrent()

        assertTrue("Deferred work must run after the signal resolves", deferredRan)
        assertFalse(
            "A completed deferred job must no longer be active",
            engine.deferredStartJobs.single().isActive,
        )
    }
}

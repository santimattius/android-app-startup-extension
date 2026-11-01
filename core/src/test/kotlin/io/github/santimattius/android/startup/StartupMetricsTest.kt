package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupMetricsTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var initializer: AppStartupInitializer

    @Before
    fun setup() {
        initializer = AppStartupInitializer(context)
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    @Test
    fun `flow receives metric after sync initializer completes`() {
        initializer.doInitialize<String>(SimpleSyncInitializer::class.java)

        val metrics = initializer.metricsFlow.replayCache
        assertEquals(1, metrics.size)
        with(metrics.first()) {
            assertEquals("SimpleSyncInitializer", initializerName)
            assertTrue(durationMs >= 0)
            assertFalse(isAsync)
            assertTrue(success)
        }
    }

    // ── Async ─────────────────────────────────────────────────────────────────

    @Test
    fun `flow receives metric after async initializer completes`() = runTest {
        initializer.doInitialize<String>(SimpleAsyncInitializer::class.java)

        val metrics = initializer.metricsFlow.replayCache
        assertEquals(1, metrics.size)
        with(metrics.first()) {
            assertEquals("SimpleAsyncInitializer", initializerName)
            assertTrue(durationMs >= 0)
            assertTrue(isAsync)
            assertTrue(success)
        }
    }

    @Test
    fun `flow receives success=false when initializer throws`() = runTest {
        runCatching { initializer.doInitialize<String>(FailingAsyncInitializer::class.java) }

        val metrics = initializer.metricsFlow.replayCache
        assertEquals(1, metrics.size)
        with(metrics.first()) {
            assertFalse(success)
            assertFalse(wasCancelled)
        }
    }

    @Test
    fun `flow marks wasCancelled=true and success=false when initializer is cancelled`() {
        // runBlocking so the launched coroutine runs eagerly until it suspends at
        // withContext(dispatcher) { delay(...) }, THEN we cancel and wait for cleanup.
        runBlocking {
            val job = launch {
                runCatching { initializer.doInitialize<String>(HangingAsyncInitializer::class.java) }
            }
            yield() // let the launched coroutine reach its first real suspension point
            job.cancel()
            job.join()
        }

        val metrics = initializer.metricsFlow.replayCache
        assertEquals(1, metrics.size)
        with(metrics.first()) {
            assertFalse(success)
            assertTrue(wasCancelled)
        }
    }

    @Test
    fun `durationMs reflects actual execution time`() = runTest {
        initializer.doInitialize<String>(SlowAsyncInitializer::class.java)

        assertTrue("durationMs should be >= 100ms", initializer.metricsFlow.replayCache.first().durationMs >= 100)
    }

    @Test
    fun `flow is empty before any initializer runs`() {
        assertTrue(initializer.metricsFlow.replayCache.isEmpty())
    }

    @Test
    fun `late collector receives all metrics via replay`() = runTest {
        initializer.doInitialize<String>(SimpleSyncInitializer::class.java)
        initializer.doInitialize<String>(SimpleAsyncInitializer::class.java)

        // Simulates a collector that subscribes after startup completes
        val metrics = initializer.metricsFlow.replayCache
        assertEquals(2, metrics.size)
        assertFalse(metrics[0].isAsync)
        assertTrue(metrics[1].isAsync)
    }

    // ── Identity, timing and error fields ────────────────────────────────────

    @Test
    fun `sync metric carries fqn, consistent timestamps and no error`() {
        initializer.doInitialize<String>(SimpleSyncInitializer::class.java)

        with(initializer.metricsFlow.replayCache.single()) {
            assertEquals(SimpleSyncInitializer::class.java.name, initializerFqn)
            assertTrue(startTimestampMs <= endTimestampMs)
            assertEquals(durationMs, endTimestampMs - startTimestampMs)
            assertTrue(processStartOffsetMs >= 0)
            assertEquals(StartupPriority.NORMAL, priority)
            assertNull(errorType)
            assertNull(errorMessage)
        }
    }

    @Test
    fun `async DEFERRED metric carries priority, fqn and timestamps consistent with duration`() = runTest {
        initializer.doInitialize<String>(DeferredAsyncInitializer::class.java)

        with(initializer.metricsFlow.replayCache.single()) {
            assertEquals(StartupPriority.DEFERRED, priority)
            assertEquals(DeferredAsyncInitializer::class.java.name, initializerFqn)
            assertTrue(startTimestampMs <= endTimestampMs)
            assertEquals(durationMs, endTimestampMs - startTimestampMs)
            assertTrue(processStartOffsetMs >= 0)
            assertNull(errorType)
        }
    }

    @Test
    fun `async failure populates error type and message`() = runTest {
        runCatching { initializer.doInitialize<String>(FailingAsyncInitializer::class.java) }

        with(initializer.metricsFlow.replayCache.single()) {
            assertFalse(success)
            assertEquals("java.lang.RuntimeException", errorType)
            assertEquals("intentional failure", errorMessage)
        }
    }

    @Test
    fun `sync failure populates error type and message`() {
        runCatching { initializer.doInitialize<String>(FailingSyncInitializer::class.java) }

        with(initializer.metricsFlow.replayCache.single()) {
            assertFalse(success)
            assertEquals("java.lang.IllegalStateException", errorType)
            assertEquals("sync boom", errorMessage)
            assertEquals(FailingSyncInitializer::class.java.name, initializerFqn)
        }
    }

    @Test
    fun `cancellation is not reported as error info`() {
        runBlocking {
            val job = launch {
                runCatching { initializer.doInitialize<String>(HangingAsyncInitializer::class.java) }
            }
            yield()
            job.cancel()
            job.join()
        }

        with(initializer.metricsFlow.replayCache.single()) {
            assertTrue(wasCancelled)
            assertNull(errorType)
            assertNull(errorMessage)
        }
    }

    @Test
    fun `cancelled before the body starts keeps timestamps consistent`() {
        // The dispatcher parks the block, so withContext suspends before create() starts; the
        // parked block is only released after cancellation, when it must not run create().
        QueuedDispatcher.pending.clear()
        runBlocking {
            val job = launch {
                runCatching { initializer.doInitialize<String>(NeverDispatchedAsyncInitializer::class.java) }
            }
            yield()
            Thread.sleep(20) // make durationMs > 0 so a derived end timestamp would be nonzero
            job.cancel()
            QueuedDispatcher.pending.forEach { it.run() }
            job.join()
        }

        with(initializer.metricsFlow.replayCache.single()) {
            assertTrue(wasCancelled)
            assertEquals(0L, startTimestampMs)
            assertEquals(0L, endTimestampMs)
            assertTrue(startTimestampMs <= endTimestampMs)
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private class SimpleSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "sync-result"
    }

    private class SimpleAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "async-result"
    }

    private class DeferredAsyncInitializer : StartupAsyncInitializer<String> {
        override fun priority() = StartupPriority.DEFERRED
        override suspend fun create(context: Context) = "deferred-result"
    }

    private class FailingSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String = throw IllegalStateException("sync boom")
    }

    private class FailingAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String =
            throw RuntimeException("intentional failure")
    }

    /**
     * Blocks the IO dispatcher for 100ms so that [System.nanoTime] captures
     * real monotonic elapsed time, independent of virtual-time advances in [runTest].
     */
    private class SlowAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            withContext(Dispatchers.IO) { Thread.sleep(100) }
            return "slow-result"
        }
    }

    private class HangingAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            kotlinx.coroutines.delay(Long.MAX_VALUE)
            return "never"
        }
    }

    private class NeverDispatchedAsyncInitializer : StartupAsyncInitializer<String> {
        override fun dispatcher(): kotlinx.coroutines.CoroutineDispatcher = QueuedDispatcher

        override suspend fun create(context: Context): String = "never"
    }

    private object QueuedDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        val pending = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            pending.add(block)
        }
    }
}

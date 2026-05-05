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

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private class SimpleSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "sync-result"
    }

    private class SimpleAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "async-result"
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
}

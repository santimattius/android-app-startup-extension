package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    private val initializer = AppStartupInitializer(context)

    @Before
    fun resetListener() {
        initializer.metricsListener = null
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    @Test
    fun `listener receives metric after sync initializer completes`() {
        val metrics = mutableListOf<StartupMetric>()
        initializer.metricsListener = StartupMetricsListener { metrics.add(it) }

        initializer.doInitialize<String>(SimpleSyncInitializer::class.java)

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
    fun `listener receives metric after async initializer completes`() = runTest {
        val metrics = mutableListOf<StartupMetric>()
        initializer.metricsListener = StartupMetricsListener { metrics.add(it) }

        initializer.doInitialize<String>(SimpleAsyncInitializer::class.java)

        assertEquals(1, metrics.size)
        with(metrics.first()) {
            assertEquals("SimpleAsyncInitializer", initializerName)
            assertTrue(durationMs >= 0)
            assertTrue(isAsync)
            assertTrue(success)
        }
    }

    @Test
    fun `listener receives success=false when initializer throws`() = runTest {
        val metrics = mutableListOf<StartupMetric>()
        initializer.metricsListener = StartupMetricsListener { metrics.add(it) }

        runCatching { initializer.doInitialize<String>(FailingAsyncInitializer::class.java) }

        assertEquals(1, metrics.size)
        assertFalse(metrics.first().success)
    }

    @Test
    fun `durationMs reflects actual execution time`() = runTest {
        val metrics = mutableListOf<StartupMetric>()
        initializer.metricsListener = StartupMetricsListener { metrics.add(it) }

        initializer.doInitialize<String>(SlowAsyncInitializer::class.java)

        assertTrue("durationMs should be >= 100ms", metrics.first().durationMs >= 100)
    }

    @Test
    fun `no metric is emitted when listener is null`() {
        initializer.metricsListener = null

        initializer.doInitialize<String>(SimpleSyncInitializer::class.java)
        // If the listener were called it would throw NPE — just verifying no crash
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
     * Blocks the IO dispatcher for 100ms so that [System.currentTimeMillis] captures
     * real wall-clock elapsed time, independent of virtual-time advances in [runTest].
     */
    private class SlowAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            withContext(Dispatchers.IO) { Thread.sleep(100) }
            return "slow-result"
        }
    }
}

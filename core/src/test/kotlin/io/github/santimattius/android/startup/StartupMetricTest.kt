package io.github.santimattius.android.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [StartupMetric] data class itself (construction defaults, `copy()`
 * roundtrip, and `componentN()` ordering). Integration-style tests that exercise the
 * emitted metrics through [AppStartupInitializer] live in [StartupMetricsTest].
 */
class StartupMetricTest {

    @Test
    fun `existing 5-arg constructor defaults the new observability fields`() {
        val metric = StartupMetric(
            initializerName = "Foo",
            durationMs = 10L,
            isAsync = true,
            success = true,
        )

        assertEquals("", metric.dispatcherName)
        assertEquals("", metric.threadName)
        assertEquals(0, metric.concurrentActiveCount)
        assertEquals(0L, metric.queueDelayMs)
    }

    @Test
    fun `copy roundtrips the new observability fields`() {
        val original = StartupMetric(
            initializerName = "Foo",
            durationMs = 10L,
            isAsync = true,
            success = true,
        )

        val copied = original.copy(
            dispatcherName = "Dispatchers.IO",
            threadName = "DefaultDispatcher-worker-1",
            concurrentActiveCount = 3,
            queueDelayMs = 42L,
        )

        assertEquals("Dispatchers.IO", copied.dispatcherName)
        assertEquals("DefaultDispatcher-worker-1", copied.threadName)
        assertEquals(3, copied.concurrentActiveCount)
        assertEquals(42L, copied.queueDelayMs)
        // Untouched fields must survive the copy unchanged.
        assertEquals(original.initializerName, copied.initializerName)
        assertEquals(original.durationMs, copied.durationMs)
    }

    @Test
    fun `componentN ordering of pre-existing fields is preserved`() {
        val metric = StartupMetric(
            initializerName = "Foo",
            durationMs = 10L,
            isAsync = true,
            success = false,
            wasCancelled = true,
        )

        assertEquals("Foo", metric.component1())
        assertEquals(10L, metric.component2())
        assertEquals(true, metric.component3())
        assertEquals(false, metric.component4())
        assertEquals(true, metric.component5())
    }
}

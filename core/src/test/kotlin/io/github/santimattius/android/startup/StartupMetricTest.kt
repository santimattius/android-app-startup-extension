package io.github.santimattius.android.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `5-arg construction defaults the added identity, timing and error fields`() {
        val metric = StartupMetric("Foo", 10L, isAsync = false, success = true)

        assertEquals(0L, metric.startTimestampMs)
        assertEquals(0L, metric.endTimestampMs)
        assertEquals(0L, metric.processStartOffsetMs)
        assertEquals(StartupPriority.NORMAL, metric.priority)
        assertEquals("", metric.initializerFqn)
        assertNull(metric.errorType)
        assertNull(metric.errorMessage)
    }

    @Test
    fun `pre-existing 9-arg positional construction still compiles and keeps values`() {
        val metric = StartupMetric("Foo", 1L, true, true, false, "Dispatchers.IO", "worker-1", 2, 3L)

        assertEquals("Dispatchers.IO", metric.dispatcherName)
        assertEquals("worker-1", metric.threadName)
        assertEquals(2, metric.concurrentActiveCount)
        assertEquals(3L, metric.queueDelayMs)
    }

    @Test
    fun `copy roundtrips the added fields`() {
        val copied = StartupMetric("Foo", 10L, true, false).copy(
            startTimestampMs = 100L,
            endTimestampMs = 110L,
            processStartOffsetMs = 90L,
            priority = StartupPriority.DEFERRED,
            initializerFqn = "com.example.Foo",
            errorType = "java.io.IOException",
            errorMessage = "disk full",
        )

        assertEquals(100L, copied.startTimestampMs)
        assertEquals(110L, copied.endTimestampMs)
        assertEquals(90L, copied.processStartOffsetMs)
        assertEquals(StartupPriority.DEFERRED, copied.priority)
        assertEquals("com.example.Foo", copied.initializerFqn)
        assertEquals("java.io.IOException", copied.errorType)
        assertEquals("disk full", copied.errorMessage)
    }

    @Test
    fun `JvmOverloads keeps the pre-existing constructor shapes available to Java callers`() {
        val string = String::class.java
        val long = Long::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!

        assertNotNull(StartupMetric::class.java.getConstructor(string, long, bool, bool))
        assertNotNull(StartupMetric::class.java.getConstructor(string, long, bool, bool, bool))
        assertNotNull(
            StartupMetric::class.java.getConstructor(string, long, bool, bool, bool, string, string, int, long)
        )
    }

    @Test
    fun `error info is stored as strings and never as a Throwable`() {
        val throwableFields = StartupMetric::class.java.declaredFields
            .filter { Throwable::class.java.isAssignableFrom(it.type) }

        assertFalse("StartupMetric must not retain a Throwable: $throwableFields", throwableFields.isNotEmpty())
    }
}

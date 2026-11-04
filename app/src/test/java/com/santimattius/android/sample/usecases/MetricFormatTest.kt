package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.StartupMetric
import io.github.santimattius.android.startup.StartupPriority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetricFormatTest {
    private fun metric(
        name: String,
        success: Boolean = true,
        cancelled: Boolean = false,
        errorMessage: String? = if (success) null else "boom",
    ) = StartupMetric(
        initializerName = name,
        durationMs = 12,
        isAsync = true,
        success = success,
        wasCancelled = cancelled,
        dispatcherName = "Dispatchers.IO",
        threadName = "DefaultDispatcher-worker-1",
        priority = StartupPriority.DEFERRED,
        initializerFqn = "pkg.$name",
        errorType = if (success) null else "java.lang.IllegalStateException",
        errorMessage = errorMessage,
    )

    @Test
    fun `outcome distinguishes success failure and cancellation`() {
        assertEquals("OK", metric("a").outcome())
        assertEquals("FAILED", metric("a", success = false).outcome())
        assertEquals("CANCELLED", metric("a", success = false, cancelled = true).outcome())
    }

    @Test
    fun `rows expose every documented field`() {
        val rows = metric("a", success = false).rows().toMap()
        assertEquals("pkg.a", rows["fqn"])
        assertEquals("async", rows["kind"])
        assertEquals("12 ms", rows["duration"])
        assertEquals("Dispatchers.IO", rows["dispatcher"])
        assertEquals("DEFERRED", rows["priority"])
        assertEquals("java.lang.IllegalStateException: boom", rows["error"])
    }

    @Test
    fun `failure without an error message does not render null`() {
        val rows = metric("a", success = false, errorMessage = null).rows().toMap()
        assertEquals("java.lang.IllegalStateException", rows["error"])
    }

    @Test
    fun `sync metrics show placeholders for dispatcher and thread`() {
        val rows = StartupMetric("S", 1, isAsync = false, success = true).rows().toMap()
        assertEquals("sync (main thread)", rows["kind"])
        assertEquals("-", rows["dispatcher"])
        assertEquals("-", rows["thread"])
        assertEquals("-", rows["error"])
    }

    @Test
    fun `accumulate emits a growing list starting empty`() =
        runTest {
            val lists = flowOf(metric("a"), metric("b")).accumulate().toList()
            assertEquals(listOf(0, 1, 2), lists.map { it.size })
            assertEquals(listOf("a", "b"), lists.last().map { it.initializerName })
        }

    @Test
    fun `collectWindow returns only the bounded replay of a late subscriber`() =
        runTest {
            val flow = MutableSharedFlow<Int>(replay = 2)
            (1..5).forEach { flow.emit(it) }
            assertEquals(listOf(4, 5), flow.replaySnapshot())
        }

    @Test
    fun `replay snapshot excludes values emitted after it is captured`() =
        runTest {
            val flow = MutableSharedFlow<Int>(replay = 1)
            flow.emit(1)
            val snapshot = flow.replaySnapshot()
            flow.emit(2)
            assertEquals(listOf(1), snapshot)
        }
}

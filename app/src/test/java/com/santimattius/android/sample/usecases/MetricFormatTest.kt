package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.StartupMetric
import io.github.santimattius.android.startup.StartupPriority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetricFormatTest {

    private fun metric(name: String, success: Boolean = true, cancelled: Boolean = false) =
        StartupMetric(
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
            errorMessage = if (success) null else "boom",
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
    fun `sync metrics show placeholders for dispatcher and thread`() {
        val rows = StartupMetric("S", 1, isAsync = false, success = true).rows().toMap()
        assertEquals("sync (main thread)", rows["kind"])
        assertEquals("-", rows["dispatcher"])
        assertEquals("-", rows["thread"])
        assertEquals("-", rows["error"])
    }

    @Test
    fun `accumulate emits a growing list starting empty`() = runTest {
        val lists = flowOf(metric("a"), metric("b")).accumulate().toList()
        assertEquals(listOf(0, 1, 2), lists.map { it.size })
        assertEquals(listOf("a", "b"), lists.last().map { it.initializerName })
    }

    @Test
    fun `collectWindow returns only the bounded replay of a late subscriber`() = runTest {
        val flow = MutableSharedFlow<Int>(replay = 2)
        (1..5).forEach { flow.emit(it) }
        assertEquals(listOf(4, 5), flow.collectWindow(100))
    }

    @Test
    fun `collectWindow also sees values emitted inside the window`() = runTest {
        val flow = MutableSharedFlow<Int>(replay = 1)
        val result = launch { assertEquals(listOf(1, 2), flow.collectWindow(100)) }
        testScheduler.runCurrent()
        flow.emit(1)
        flow.emit(2)
        result.join()
    }
}

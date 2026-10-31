package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupMetricsBusTest {

    @After
    fun reset() {
        AppStartupInitializer.configure { }
        ShadowLog.clear()
    }

    private fun metric(index: Int) =
        StartupMetric("Init$index", durationMs = index.toLong(), isAsync = false, success = true)

    private fun StartupMetricsBus.publishAll(count: Int) = repeat(count) { publish(metric(it)) }

    @Test
    fun `late collector receives every metric in order when within the bound`() = runTest {
        val bus = StartupMetricsBus(capacity = 256)
        bus.publishAll(10)

        val received = bus.metricsFlow.take(10).toList()

        assertEquals((0 until 10).map { "Init$it" }, received.map { it.initializerName })
    }

    @Test
    fun `late collector gets only the most recent metrics in order once the bound is exceeded`() = runTest {
        val bus = StartupMetricsBus(capacity = 256)
        bus.publishAll(300)

        val received = bus.metricsFlow.take(256).toList()

        assertEquals(256, bus.metricsFlow.replayCache.size)
        assertEquals((44 until 300).map { "Init$it" }, received.map { it.initializerName })
    }

    @Test
    fun `publishing past the bound never suspends the emitter`() {
        val bus = StartupMetricsBus(capacity = 4)

        bus.publishAll(1_000) // plain non-suspending calls: returning at all proves no backpressure

        assertEquals(listOf(996, 997, 998, 999), bus.metricsFlow.replayCache.map { it.durationMs.toInt() })
    }

    @Test
    fun `live collector receives every metric with no loss`() = runTest {
        val bus = StartupMetricsBus(capacity = 8)
        val received = mutableListOf<StartupMetric>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { bus.metricsFlow.collect { received += it } }

        bus.publishAll(100)
        job.cancel()

        assertEquals((0 until 100).map { "Init$it" }, received.map { it.initializerName })
    }

    @Test
    fun `warns exactly once on the first dropped metric`() {
        StartupExtensionLogger.enable(true)
        val bus = StartupMetricsBus(capacity = 3)

        bus.publishAll(3)
        assertEquals(0, warnings().size)
        bus.publishAll(10)

        assertEquals(1, warnings().size)
        assertTrue(warnings().single().msg.contains("3"))
    }

    @Test
    fun `configured capacity is honored by the initializer`() {
        AppStartupInitializer.configure { metricsReplayCapacity = 2 }
        val initializer = AppStartupInitializer(mockk<Context>(relaxed = true))

        initializer.doInitialize<String>(SyncA::class.java)
        initializer.doInitialize<String>(SyncB::class.java)
        initializer.doInitialize<String>(SyncC::class.java)

        assertEquals(
            listOf("SyncB", "SyncC"),
            initializer.metricsFlow.replayCache.map { it.initializerName },
        )
    }

    private fun warnings() = ShadowLog.getLogsForTag("StartupLogger").filter { it.type == android.util.Log.WARN }

    private class SyncA : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"
    }

    private class SyncB : StartupSyncInitializer<String> {
        override fun create(context: Context) = "b"
    }

    private class SyncC : StartupSyncInitializer<String> {
        override fun create(context: Context) = "c"
    }
}

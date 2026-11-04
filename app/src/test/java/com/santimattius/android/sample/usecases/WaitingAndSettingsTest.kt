package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.AppStartupConfig
import io.github.santimattius.android.startup.AsyncInitializerStrategy
import io.github.santimattius.android.startup.SyncOrderingStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaitingAndSettingsTest {
    private fun build(settings: StartupSettings) = AppStartupConfig.Builder().also { settings.applyTo(it) }

    @Test
    fun `a wait that times out reports the timeout and the resulting state`() =
        runTest {
            var done = false
            val lines =
                WaitingCases.measure("w", { done }, { "IN_PROGRESS" }) {
                    withTimeout(800) { delay(5_000) }
                }
            assertTrue(lines[1], lines[1].startsWith("w: TimeoutCancellationException"))
            assertEquals("state after: IN_PROGRESS, isAllStartedJobsDone(): false", lines[2])
            done = true
        }

    @Test
    fun `a wait that finishes reports a normal return`() =
        runTest {
            val lines = WaitingCases.measure("w", { true }, { "COMPLETED" }) { delay(10) }
            assertTrue(lines[1].startsWith("w: returned normally"))
            assertEquals("state after: COMPLETED, isAllStartedJobsDone(): true", lines[2])
        }

    @Test
    fun `caller cancellation does not reach the protected startup wait`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var waitCompleted = false
            val job =
                launch {
                    WaitingCases.measure("w", { false }, { "IN_PROGRESS" }) {
                        entered.complete(Unit)
                        release.await()
                        waitCompleted = true
                    }
                }
            entered.await()

            job.cancel()
            runCurrent()
            assertFalse(job.isCompleted)
            assertFalse(waitCompleted)

            release.complete(Unit)
            job.join()
            assertTrue(waitCompleted)
        }

    @Test
    fun `instrumentation transaction restores the captured settings after failure`() =
        runTest {
            val previous = SampleStartup.current
            val baseline = StartupSettings(debugLogging = true, deferredStartupTimeoutMs = 1_234)
            try {
                SampleStartup.apply(baseline)
                try {
                    SampleStartup.withDispatcherInstrumentation { error("boom") }
                } catch (_: IllegalStateException) {
                    // Expected: the assertion is the restored global configuration below.
                }
                assertEquals(baseline, SampleStartup.current)
            } finally {
                SampleStartup.apply(previous)
            }
        }

    @Test
    fun `instrumentation transaction restores captured settings after cancellation`() =
        runTest {
            val previous = SampleStartup.current
            val baseline = StartupSettings(debugLogging = true, deferredStartupTimeoutMs = 1_234)
            try {
                SampleStartup.apply(baseline)
                val entered = CompletableDeferred<Unit>()
                val job = launch {
                    SampleStartup.withDispatcherInstrumentation {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()
                assertTrue(SampleStartup.current.dispatcherInstrumentation)

                job.cancelAndJoin()

                assertEquals(baseline, SampleStartup.current)
            } finally {
                SampleStartup.apply(previous)
            }
        }

    @Test
    fun `default settings keep every library default`() {
        val b = build(StartupSettings())
        assertNull(b.maxConcurrentAsyncInitializers)
        assertSame(Dispatchers.Default, b.defaultAsyncDispatcher)
        assertEquals(SyncOrderingStrategy.Lazy, b.syncOrderingStrategy)
        assertEquals(AsyncInitializerStrategy.Concurrent, b.asyncInitializerStrategy)
        assertEquals(false, b.dispatcherInstrumentationEnabled)
        assertNull(b.firstFrameSignal)
        assertEquals(5_000L, b.deferredStartupTimeoutMs)
    }

    @Test
    fun `every option is mapped onto the builder`() {
        val b =
            build(
                StartupSettings(
                    maxConcurrentAsyncInitializers = 1,
                    useIoAsDefaultDispatcher = true,
                    topologicalSync = true,
                    validatedAsync = true,
                    dispatcherInstrumentation = true,
                    strictModeCheck = true,
                    strictModeConcurrencyThreshold = 1,
                    deferredStartupTimeoutMs = 3_000,
                    neverSignalFirstFrame = true,
                    metricsReplayCapacity = 2,
                    debugLogging = true,
                ),
            )
        assertEquals(1, b.maxConcurrentAsyncInitializers)
        assertSame(Dispatchers.IO, b.defaultAsyncDispatcher)
        assertEquals(SyncOrderingStrategy.Topological, b.syncOrderingStrategy)
        assertEquals(AsyncInitializerStrategy.Validated, b.asyncInitializerStrategy)
        assertTrue(b.dispatcherInstrumentationEnabled && b.strictModeCheckEnabled && b.debugLoggingEnabled)
        assertEquals(1, b.strictModeConcurrencyThreshold)
        assertEquals(3_000L, b.deferredStartupTimeoutMs)
        assertSame(NeverFirstFrameSignal, b.firstFrameSignal)
        assertEquals(2, b.metricsReplayCapacity)
    }
}

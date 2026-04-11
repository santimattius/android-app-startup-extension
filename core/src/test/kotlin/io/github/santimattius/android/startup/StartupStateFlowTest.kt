package io.github.santimattius.android.startup

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 4.2 — StateFlow de estado de inicialización.
 *
 * A shared [TestCoroutineScheduler] is injected into both [runTest] and the engine's
 * dispatcher so all [delay] calls are virtual and state transitions are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupStateFlowTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)
    private val context: Context = mockk(relaxed = true)

    private val initializer = AppStartupInitializer(context, testDispatcher)
    private val coroutinesEngine get() = initializer.coroutinesEngine

    // ── Spec-Test 1 ───────────────────────────────────────────────────────────

    @Test
    fun `startupState starts as IDLE`() = runTest(testDispatcher) {
        assertEquals(StartupState.IDLE, initializer.startupState.value)
    }

    @Test
    fun `startupState transitions to IN_PROGRESS when a job is launched`() =
        runTest(testDispatcher) {
            assertEquals(StartupState.IDLE, initializer.startupState.value)

            coroutinesEngine.launchStartJob { delay(1_000) }

            assertEquals(StartupState.IN_PROGRESS, initializer.startupState.value)
        }

    // ── Spec-Test 2 ───────────────────────────────────────────────────────────

    @Test
    fun `startupState transitions to COMPLETED after awaitAllStartJobs`() =
        runTest(testDispatcher) {
            coroutinesEngine.launchStartJob { delay(10) }

            initializer.awaitAllStartJobs()

            assertEquals(StartupState.COMPLETED, initializer.startupState.value)
        }

    // ── Spec-Test 3 ───────────────────────────────────────────────────────────

    @Test
    fun `startupState emits states in correct order`() = runTest(testDispatcher) {
        val states = mutableListOf<StartupState>()

        // Start collecting before any job is launched so IDLE is captured.
        val collectorJob = launch { initializer.startupState.collect { states.add(it) } }

        // Drain the scheduler: the collector runs, receives IDLE (current value), and suspends.
        advanceUntilIdle()

        coroutinesEngine.launchStartJob { delay(50) }  // state → IN_PROGRESS
        initializer.awaitAllStartJobs()                // state → COMPLETED

        // Drain the scheduler so the collector coroutine processes the COMPLETED emission
        // before we cancel it (StateFlow notification is scheduled, not synchronous).
        advanceUntilIdle()
        collectorJob.cancel()

        assertEquals(
            listOf(StartupState.IDLE, StartupState.IN_PROGRESS, StartupState.COMPLETED),
            states
        )
    }

    // ── ERROR state ───────────────────────────────────────────────────────────

    @Test
    fun `startupState transitions to ERROR when an initializer job throws`() =
        runTest(testDispatcher) {
            coroutinesEngine.launchStartJob<Unit> { throw RuntimeException("intentional failure") }

            runCatching { initializer.awaitAllStartJobs() }

            assertEquals(StartupState.ERROR, initializer.startupState.value)
        }
}

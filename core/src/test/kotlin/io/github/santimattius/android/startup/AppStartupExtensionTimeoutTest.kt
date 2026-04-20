package io.github.santimattius.android.startup

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 2.1 — Timeout en awaitAllStartJobs
 *
 * A shared [TestCoroutineScheduler] is injected into both [runTest] and the engine's dispatcher
 * so that all delays (inside launchStartJob *and* withTimeout) are virtual and progress
 * deterministically without wall-clock waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartupExtensionTimeoutTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)
    private val context: Context = mockk(relaxed = true)

    // Inject testDispatcher so the engine's launchStartJob delays are also virtual
    private val initializer = AppStartupInitializer(context, testDispatcher)

    @Test
    fun `awaitAllStartJobs with timeout throws TimeoutCancellationException when exceeded`() =
        runTest(testDispatcher) {
            // Given: a job that takes much longer than the timeout
            initializer.coroutinesEngine.launchStartJob { delay(5_000) }

            // When
            var caught: TimeoutCancellationException? = null
            try {
                initializer.awaitAllStartJobs(timeoutMs = 100)
            } catch (e: TimeoutCancellationException) {
                caught = e
            }

            // Then
            assertNotNull("Expected TimeoutCancellationException but none was thrown", caught)
        }

    @Test
    fun `awaitAllStartJobs without timeout completes normally`() =
        runTest(testDispatcher) {
            // Given
            initializer.coroutinesEngine.launchStartJob { delay(100) }

            // When / Then: completes without throwing
            initializer.awaitAllStartJobs()
        }

    @Test
    fun `awaitAllStartJobs with timeout completes if jobs finish in time`() =
        runTest(testDispatcher) {
            // Given: job that finishes well before the timeout
            initializer.coroutinesEngine.launchStartJob { delay(10) }

            // When
            var caught: TimeoutCancellationException? = null
            try {
                initializer.awaitAllStartJobs(timeoutMs = 5_000)
            } catch (e: TimeoutCancellationException) {
                caught = e
            }

            // Then: timeout did not fire
            assertNull("Expected no TimeoutCancellationException but one was thrown", caught)
        }

    @Test
    fun `awaitAllStartJobs cancels in-flight jobs when timeout elapses`() =
        runTest(testDispatcher) {
            // Given: a job that would run indefinitely
            initializer.coroutinesEngine.launchStartJob { delay(Long.MAX_VALUE) }
            val job = initializer.coroutinesEngine.startJobs.first()

            // When: timeout fires
            runCatching { initializer.awaitAllStartJobs(timeoutMs = 100) }

            // Then: the deferred was cancelled, not left running as a zombie
            assertNotNull(job)
            assert(!job.isActive) { "Expected job to be cancelled after timeout, but it is still active" }
        }
}

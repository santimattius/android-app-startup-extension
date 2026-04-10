package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartupInitializerMutexTest {

    private val context1: Context = mockk(relaxed = true)
    private val context2: Context = mockk(relaxed = true)

    /**
     * Two instances must use distinct mutexes.
     *
     * With a shared companion mutex both calls would serialize, advancing virtual time to
     * ~2 * DELAY_MS. With per-instance mutexes both run in parallel, advancing virtual time
     * to ~DELAY_MS. The assertion on [currentTime] is deterministic: TestCoroutineScheduler
     * drives delay() virtually, so wall-clock speed never affects the result.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `two independent instances do not share mutex lock`() = runTest {
        val instance1 = AppStartupInitializer(context1)
        val instance2 = AppStartupInitializer(context2)

        val job1 = async { instance1.doInitialize<String>(SlowAsyncInitializer::class.java) }
        val job2 = async { instance2.doInitialize<String>(SlowAsyncInitializer::class.java) }
        awaitAll(job1, job2)

        assertTrue(
            "Expected parallel execution (~${DELAY_MS}ms virtual), but currentTime=${testScheduler.currentTime} — " +
                "a shared mutex would serialize both calls to ~${DELAY_MS * 2}ms",
            testScheduler.currentTime < DELAY_MS * 2
        )
    }

    private class SlowAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            delay(DELAY_MS)
            return "slow"
        }
    }

    companion object {
        private const val DELAY_MS = 150L
    }
}

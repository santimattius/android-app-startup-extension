package io.github.santimattius.android.startup

import android.app.Activity
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec: startup-first-frame-signal — production Android lifecycle path.
 *
 * Verifies that [AndroidFirstFrameSignal] completes once the first activity is created and its
 * decor view posts the draw callback. Scheduling under test uses injected fakes; this test covers
 * the default production implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidFirstFrameSignalTest {

    @Test
    fun `await completes after the first activity decor view posts`() {
        val application = RuntimeEnvironment.getApplication()
        val signal = AndroidFirstFrameSignal(application)
        val done = CountDownLatch(1)

        val waiter = Thread {
            runBlocking {
                signal.await()
                done.countDown()
            }
        }
        waiter.start()

        Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "await must complete after the first activity schedules its first draw",
            done.await(3, TimeUnit.SECONDS),
        )
        waiter.join(1_000)
    }

    @Test
    fun `await completes synchronously on the test thread after activity setup`() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val signal = AndroidFirstFrameSignal(application)

        Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        withTimeout(2_000L) { signal.await() }
    }
}

package io.github.santimattius.android.startup

import android.app.Activity
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec: startup-first-frame-signal — production Android lifecycle path.
 *
 * Verifies that [AndroidFirstFrameSignal] completes exactly once when the first activity's decor
 * view receives its first draw callback. Scheduling under test uses injected fakes; this test
 * covers the default production implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidFirstFrameSignalTest {
    @Test
    fun `await completes only after the first activity decor view draws and only once`() {
        val application = RuntimeEnvironment.getApplication()
        val signal = AndroidFirstFrameSignal(application)
        val completionCount = AtomicInteger()
        val done = CountDownLatch(1)

        val waiter =
            Thread {
                runBlocking {
                    signal.await()
                    completionCount.incrementAndGet()
                    done.countDown()
                }
            }
        waiter.start()

        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "idling the main looper without a draw must not complete the first-frame signal",
            done.await(200, TimeUnit.MILLISECONDS),
        )

        activity.window.decorView.viewTreeObserver
            .dispatchOnDraw()

        assertTrue(
            "await must complete when the first activity decor view receives a draw callback",
            done.await(3, TimeUnit.SECONDS),
        )
        activity.window.decorView.viewTreeObserver
            .dispatchOnDraw()
        assertEquals("the first-frame signal must complete exactly once", 1, completionCount.get())
        waiter.join(1_000)
    }

    @Test
    fun `await completes synchronously on the test thread after activity decor view draws`() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            val signal = AndroidFirstFrameSignal(application)

            val activity = Robolectric.buildActivity(Activity::class.java).create().get()
            activity.window.decorView.viewTreeObserver
                .dispatchOnDraw()

            withTimeout(2_000L) { signal.await() }
        }
}

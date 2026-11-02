package com.santimattius.android.sample

import android.os.Build
import android.view.ViewTreeObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], application = android.app.Application::class)
class ReportFullyDrawnTest {

    @Test
    fun `not reported before the content is rendered`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val reports = countReports(controller)

        controller.create()
        idleFrames()

        assertFalse(controller.get().fullyDrawnReporter.isFullyDrawnReported)
        assertEquals(0, reports.get())
    }

    @Test
    fun `reported exactly once after content is rendered`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val reports = countReports(controller)

        controller.setup().visible()
        idleFrames()
        dispatchDraw(controller)
        idleFrames()
        // Further lifecycle passes and draws must not report again.
        controller.pause().resume()
        dispatchDraw(controller)
        idleFrames()

        assertTrue(controller.get().fullyDrawnReporter.isFullyDrawnReported)
        assertEquals(1, reports.get())
    }

    // Compose applies its first composition on a Choreographer frame: advance the paused clock.
    private fun idleFrames() = repeat(5) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }

    // Robolectric never draws, but the FullyDrawnReporter only fires from a draw pass, so
    // dispatch one explicitly (ViewTreeObserver.dispatchOnDraw is @hide; reflection is test-only).
    private fun dispatchDraw(controller: ActivityController<MainActivity>) {
        val observer = controller.get().window.decorView.viewTreeObserver
        ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnDraw")
            .apply { isAccessible = true }
            .invoke(observer)
    }

    private fun countReports(controller: ActivityController<MainActivity>) =
        AtomicInteger().also { count ->
            controller.get().fullyDrawnReporter.addOnReportDrawnListener { count.incrementAndGet() }
        }
}

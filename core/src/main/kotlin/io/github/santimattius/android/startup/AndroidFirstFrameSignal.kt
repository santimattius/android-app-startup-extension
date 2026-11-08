package io.github.santimattius.android.startup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [FirstFrameSignal] backed by a [CompletableDeferred]. It completes exactly once, only
 * when the decor view of the **first created activity** invokes an [ViewTreeObserver.OnDrawListener].
 * Idling the main looper, posting work to the decor view, or merely creating that activity does not
 * complete the signal.
 *
 * Resolution strategy:
 * - `context.applicationContext as? Application` — if non-null, register a one-shot
 *   [Application.ActivityLifecycleCallbacks]. The first `onActivityCreated` unregisters that
 *   callback and registers an [ViewTreeObserver.OnDrawListener] on its decor view. The listener
 *   completes the signal, then cleanup is posted so removal happens after `onDraw()` returns.
 * - if no `Application` or decor view is available (headless/no-UI process), the deferred **never
 *   self-completes**; the deferred-startup gate relies on the `deferredStartupTimeoutMs` fallback.
 *
 * Lifecycle and draw-listener registration/removal run on the main thread. Cancelling one caller of
 * [await] does not cancel the shared deferred, so remaining deferred startup roots keep their
 * first-draw gate and the timeout fallback remains available.
 *
 * ## Testing
 *
 * Core scheduling is verified through injected fakes via the [FirstFrameSignal] seam. The Android
 * lifecycle path is covered by [AndroidFirstFrameSignalTest].
 *
 * ## Leak safety
 *
 * Instances are created lazily by the scheduler. The activity callback is removed when the first
 * activity is observed, and draw-listener removal is posted immediately after the deferred
 * completes, so neither callback remains beyond the terminal draw traversal.
 */
internal class AndroidFirstFrameSignal(
    context: Context,
) : FirstFrameSignal {
    private val firstFrame = CompletableDeferred<Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val application = context.applicationContext as? Application
    private val firstActivityObserved = AtomicBoolean(false)

    private var trackedDecorView: View? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null

    private val lifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {
                runOnMain { observeFirstActivityDraw(activity) }
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    init {
        firstFrame.invokeOnCompletion { mainHandler.post(::clearCallbacks) }
        application?.let { app -> runOnMain { app.registerActivityLifecycleCallbacks(lifecycleCallbacks) } }
    }

    override suspend fun await() {
        firstFrame.await()
    }

    private fun observeFirstActivityDraw(activity: Activity) {
        if (!firstActivityObserved.compareAndSet(false, true)) return

        application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        val decorView = activity.window?.decorView ?: return
        val listener =
            object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    firstFrame.complete(Unit)
                }
            }
        trackedDecorView = decorView
        drawListener = listener
        decorView.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun clearCallbacks() {
        application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        removeDrawListener()
    }

    private fun removeDrawListener() {
        val decorView = trackedDecorView
        val listener = drawListener
        if (decorView != null && listener != null && decorView.viewTreeObserver.isAlive) {
            decorView.viewTreeObserver.removeOnDrawListener(listener)
        }
        trackedDecorView = null
        drawListener = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}

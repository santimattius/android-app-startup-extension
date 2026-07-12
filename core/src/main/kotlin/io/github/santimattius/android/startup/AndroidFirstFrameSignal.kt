package io.github.santimattius.android.startup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred

/**
 * Default [FirstFrameSignal] backed by a [CompletableDeferred], completed on the **first activity's
 * first draw**.
 *
 * Resolution strategy:
 * - `context.applicationContext as? Application` — if non-null, register a one-shot
 *   [Application.ActivityLifecycleCallbacks]. On the first `onActivityCreated`, post a completion to
 *   the activity's decor view (which runs after the first draw traversal is scheduled) and
 *   unregister the callback immediately so nothing is retained.
 * - if the cast is `null` (headless process / no `Application`), the deferred **never
 *   self-completes**; the deferred-startup gate relies on the `deferredStartupTimeoutMs` fallback.
 *
 * ## Testing
 *
 * Core scheduling is verified through injected fakes via the [FirstFrameSignal] seam. The Android
 * lifecycle path is covered by [AndroidFirstFrameSignalTest].
 *
 * ## Leak safety
 *
 * Instances are created lazily by the scheduler and the lifecycle callback unregisters itself on the
 * first draw, so no `Activity` is retained past the first frame.
 */
internal class AndroidFirstFrameSignal(context: Context) : FirstFrameSignal {

    private val firstFrame = CompletableDeferred<Unit>()

    init {
        val application = context.applicationContext as? Application
        application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Posting to the decor view completes right after the first draw is scheduled; a
                // close-enough "first frame" signal that requires no Choreographer coupling.
                activity.window?.decorView?.post { firstFrame.complete(Unit) }
                    ?: firstFrame.complete(Unit)
                application.unregisterActivityLifecycleCallbacks(this)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override suspend fun await() {
        firstFrame.await()
    }
}

package io.github.santimattius.android.startup

/**
 * An injectable seam that resolves when the application's **first frame** has been drawn.
 *
 * `DEFERRED` async initializers (see [StartupPriority.DEFERRED]) suspend on [await] before their
 * `create()` is invoked, so their work never competes with UI-critical startup for the first frame.
 *
 * ## Why a seam?
 *
 * Core scheduling depends **only** on this interface and never hardcodes `Choreographer`/`Activity`
 * detection. This keeps the deferred-startup logic drivable under Robolectric/JVM with a simple
 * [kotlinx.coroutines.CompletableDeferred]-backed fake, and lets callers supply a custom trigger
 * (e.g. a specific screen becoming interactive) via [AppStartupConfig.firstFrameSignal].
 *
 * The library provides an internal Android default (registered lazily by the scheduler) that
 * completes on the first activity's first draw. When no `Application`/UI is available (headless
 * processes), the default never self-completes and the deferred work is flushed by the
 * [AppStartupConfig.deferredStartupTimeoutMs] fallback instead.
 *
 * > **Leak note:** a custom [FirstFrameSignal] held in [AppStartupConfig] lives for the process
 * > lifetime — never let an implementation retain an `Activity`/`Fragment`.
 */
interface FirstFrameSignal {

    /**
     * Suspends until the first frame has been drawn (or the signal is otherwise resolved).
     *
     * Implementations MUST be safe to `await()` from multiple coroutines and MUST resume all of
     * them once the frame is signalled. Returning immediately means "the frame has already drawn".
     */
    suspend fun await()
}

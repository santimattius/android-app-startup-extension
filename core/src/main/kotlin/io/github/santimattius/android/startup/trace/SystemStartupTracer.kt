package io.github.santimattius.android.startup.trace

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * [StartupTracer] backed by `android.os.Trace`. Below API 29 sync sections stay on (legacy
 * behavior) while async sections and counters are no-ops and never throw.
 */
internal object SystemStartupTracer : StartupTracer {

    private val cookies = AtomicInteger(0)

    private val atLeastQ: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override val isEnabled: Boolean
        get() = if (atLeastQ) Trace.isEnabled() else true

    override val isAsyncEnabled: Boolean
        get() = atLeastQ && Trace.isEnabled()

    override fun beginSection(name: String) = Trace.beginSection(name)

    override fun endSection() = Trace.endSection()

    override fun beginAsyncSection(name: String, cookie: Int) {
        if (atLeastQ) Trace.beginAsyncSection(name, cookie)
    }

    override fun endAsyncSection(name: String, cookie: Int) {
        if (atLeastQ) Trace.endAsyncSection(name, cookie)
    }

    override fun setCounter(name: String, value: Long) {
        if (atLeastQ) Trace.setCounter(name, value)
    }

    /** Zero-length slice on the current thread (no public instant API). */
    override fun marker(name: String) {
        if (!isEnabled) return
        Trace.beginSection(name)
        Trace.endSection()
    }

    override fun nextCookie(): Int = cookies.incrementAndGet()
}

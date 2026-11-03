package io.github.santimattius.android.startup.trace

/**
 * Internal tracing seam. The system-trace implementation is [SystemStartupTracer]; tests inject a
 * recording double. Sync sections are thread-bound and MUST NOT span a suspension point; any block
 * that can suspend uses an async section whose [nextCookie] is captured at begin so begin/end pair
 * across dispatcher hops.
 */
internal interface StartupTracer {
    val isEnabled: Boolean
    val isAsyncEnabled: Boolean
    fun beginSection(name: String)
    fun endSection()
    fun beginAsyncSection(name: String, cookie: Int)
    fun endAsyncSection(name: String, cookie: Int)
    fun setCounter(name: String, value: Long)
    fun marker(name: String)
    fun nextCookie(): Int
}

/** Open async slice handle; allocated only when tracing is enabled. */
internal class AsyncSpan(val name: String, val cookie: Int)

/**
 * Sync section for a non-suspending block. The name lambda is evaluated only when enabled, and the
 * end always runs in `finally`.
 */
internal inline fun <T> StartupTracer.section(name: () -> String, block: () -> T): T {
    val opened = openSection(name)
    try {
        return block()
    } finally {
        closeSection(opened)
    }
}

/**
 * Async section for a block that may suspend. Not `suspend` itself: the inlined [block] may call
 * suspend functions when the caller is a suspend function.
 */
internal inline fun <T> StartupTracer.asyncSection(name: () -> String, block: () -> T): T {
    val span = openAsyncSection(name)
    try {
        return block()
    } finally {
        closeAsyncSection(span)
    }
}

/** Begins a sync section if enabled; returns whether one was opened (pass to [closeSection]). */
internal inline fun StartupTracer.openSection(name: () -> String): Boolean {
    if (!isEnabled) return false
    beginSection(name())
    return true
}

internal fun StartupTracer.closeSection(opened: Boolean) {
    if (opened) endSection()
}

/** Begins an async section if enabled; returns the handle to pass to [closeAsyncSection], else null. */
internal inline fun StartupTracer.openAsyncSection(name: () -> String): AsyncSpan? {
    if (!isAsyncEnabled) return null
    val span = AsyncSpan(name(), nextCookie())
    beginAsyncSection(span.name, span.cookie)
    return span
}

internal fun StartupTracer.closeAsyncSection(span: AsyncSpan?) {
    if (span != null) endAsyncSection(span.name, span.cookie)
}

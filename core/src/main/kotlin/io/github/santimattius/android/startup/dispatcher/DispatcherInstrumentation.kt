package io.github.santimattius.android.startup.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-startup registry of [InstrumentedDispatcher]s. A dispatcher shared by several initializers is
 * wrapped once (keyed by identity) so they all feed the same snapshot entry. Never touches global
 * dispatchers: it only returns wrappers for the dispatchers it is handed.
 */
internal class DispatcherInstrumentation {

    private val wrappers = ConcurrentHashMap<CoroutineDispatcher, InstrumentedDispatcher>()
    private val labels = ConcurrentHashMap<String, Int>()

    fun wrap(delegate: CoroutineDispatcher): InstrumentedDispatcher =
        delegate as? InstrumentedDispatcher
            ?: wrappers.getOrPut(delegate) { InstrumentedDispatcher(delegate, uniqueLabel(deriveDispatcherLabel(delegate))) }

    fun snapshots(): Map<String, DispatcherSnapshot> =
        wrappers.values.map { it.snapshot() }.associateBy { it.label }

    private fun uniqueLabel(base: String): String {
        val n = labels.merge(base, 1, Int::plus)!!
        return if (n == 1) base else "$base#$n"
    }
}

private val VOLATILE_STATS = Regex("\\[.*]")
private val IDENTITY_HASH = Regex("@[0-9a-fA-F]+")
private val PACKAGE_PREFIX = Regex("^(?:[a-z_][a-z0-9_]*\\.)+(?=[A-Z_$])")

/**
 * Derives a human-readable label that is stable across runs from the dispatcher's `toString()`:
 * bracketed statistics (`[Running, pool size = 2, ...]`), the identity hash (`@1a2b3c`) and a
 * leading lowercase package path are removed, so `java.util.concurrent.ThreadPoolExecutor@1a[...]`
 * becomes `ThreadPoolExecutor` while `Dispatchers.IO` is kept. Falls back to the class name when
 * nothing readable remains.
 */
internal fun deriveDispatcherLabel(dispatcher: CoroutineDispatcher): String =
    dispatcher.toString()
        .replace(VOLATILE_STATS, "")
        .replace(IDENTITY_HASH, "")
        .trim()
        .replace(PACKAGE_PREFIX, "")
        .ifBlank { dispatcher.javaClass.name.substringAfterLast('.') }

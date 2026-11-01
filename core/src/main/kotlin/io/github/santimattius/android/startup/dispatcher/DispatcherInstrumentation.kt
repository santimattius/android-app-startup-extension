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
            ?: wrappers.getOrPut(delegate) { InstrumentedDispatcher(delegate, uniqueLabel(delegate.toString())) }

    fun snapshots(): Map<String, DispatcherSnapshot> =
        wrappers.values.map { it.snapshot() }.associateBy { it.label }

    private fun uniqueLabel(base: String): String {
        val n = labels.merge(base, 1, Int::plus)!!
        return if (n == 1) base else "$base#$n"
    }
}

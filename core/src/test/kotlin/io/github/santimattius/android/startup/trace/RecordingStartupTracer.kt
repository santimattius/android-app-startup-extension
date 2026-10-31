package io.github.santimattius.android.startup.trace

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Test double for the [StartupTracer] seam: records every call in order, thread-safely. */
internal class RecordingStartupTracer(
    @Volatile var enabled: Boolean = true,
) : StartupTracer {

    sealed interface Event {
        val name: String
        data class Begin(override val name: String, val thread: String) : Event
        data class End(override val name: String, val thread: String) : Event
        data class AsyncBegin(override val name: String, val cookie: Int) : Event
        data class AsyncEnd(override val name: String, val cookie: Int) : Event
        data class Counter(override val name: String, val value: Long) : Event
        data class Marker(override val name: String) : Event
    }

    val events = CopyOnWriteArrayList<Event>()
    private val cookies = AtomicInteger(0)
    private val stack = ThreadLocal.withInitial { ArrayDeque<String>() }

    override val isEnabled: Boolean get() = enabled
    override val isAsyncEnabled: Boolean get() = enabled

    override fun beginSection(name: String) {
        stack.get().addLast(name)
        events += Event.Begin(name, Thread.currentThread().name)
    }

    override fun endSection() {
        // The platform API carries no name on end; pair with this thread's innermost open section.
        val name = stack.get().removeLastOrNull() ?: "<unbalanced>"
        events += Event.End(name, Thread.currentThread().name)
    }

    override fun beginAsyncSection(name: String, cookie: Int) {
        events += Event.AsyncBegin(name, cookie)
    }

    override fun endAsyncSection(name: String, cookie: Int) {
        events += Event.AsyncEnd(name, cookie)
    }

    override fun setCounter(name: String, value: Long) {
        events += Event.Counter(name, value)
    }

    override fun marker(name: String) {
        events += Event.Marker(name)
    }

    override fun nextCookie(): Int = cookies.incrementAndGet()

    /** Index of the first event matching [predicate], or -1. */
    fun indexOf(predicate: (Event) -> Boolean): Int = events.indexOfFirst(predicate)

    fun asyncBeginIndex(name: String): Int = indexOf { it is Event.AsyncBegin && it.name == name }
    fun asyncEndIndex(name: String): Int = indexOf { it is Event.AsyncEnd && it.name == name }
    fun syncBeginIndex(name: String): Int = indexOf { it is Event.Begin && it.name == name }
    fun syncEndIndex(name: String): Int = indexOf { it is Event.End && it.name == name }

    /** True when every async begin has exactly one end with the same name and cookie, and vice versa. */
    fun asyncBalanced(): Boolean {
        val begins = events.filterIsInstance<Event.AsyncBegin>().map { it.name to it.cookie }
        val endsList = events.filterIsInstance<Event.AsyncEnd>().map { it.name to it.cookie }
        return begins.size == begins.toSet().size && begins.toSet() == endsList.toSet() && begins.size == endsList.size
    }

    /** True when sync begins/ends form a properly nested stack on every thread. */
    fun syncBalanced(): Boolean {
        val stacks = HashMap<String, ArrayDeque<String>>()
        events.forEach { e ->
            when (e) {
                is Event.Begin -> stacks.getOrPut(e.thread) { ArrayDeque() }.addLast(e.name)
                is Event.End -> {
                    val stack = stacks[e.thread] ?: return false
                    if (stack.isEmpty() || stack.removeLast() != e.name) return false
                }
                else -> Unit
            }
        }
        return stacks.values.all { it.isEmpty() }
    }
}

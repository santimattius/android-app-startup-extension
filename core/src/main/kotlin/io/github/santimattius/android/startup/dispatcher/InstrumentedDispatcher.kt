package io.github.santimattius.android.startup.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * Measuring wrapper around ONE effective initializer dispatcher. Opt-in, debug/measurement only.
 *
 * Wrapping `Dispatchers.IO` reduces its thread-pool elasticity (the wrapper hides the
 * `limitedParallelism`/blocking-hint behavior and `withContext` can no longer take the
 * same-dispatcher fast path, costing one extra hop), so do not enable it in production.
 *
 * All counters are lock-free atomics; the active count is decremented in `finally`, so a failing
 * block never corrupts them.
 */
internal class InstrumentedDispatcher(
    private val delegate: CoroutineDispatcher,
    val label: String,
    private val nanoTime: () -> Long = System::nanoTime,
) : CoroutineDispatcher() {

    private val active = AtomicInteger(0)
    private val peakConcurrency = AtomicInteger(0)
    private val maxQueueDelayMs = AtomicLong(0)
    private val totalDispatched = AtomicLong(0)

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        totalDispatched.incrementAndGet()
        delegate.dispatch(context, measured(block))
    }

    // dispatchYield is @InternalCoroutinesApi; it is the only way to keep the delegate's yield
    // behavior (e.g. the Dispatchers.Default/IO scheduler fairness hint) instead of falling back
    // to dispatch(). The opt-in is limited to this override.
    @OptIn(InternalCoroutinesApi::class)
    override fun dispatchYield(context: CoroutineContext, block: Runnable) {
        totalDispatched.incrementAndGet()
        delegate.dispatchYield(context, measured(block))
    }

    fun snapshot() = DispatcherSnapshot(
        label = label,
        peakConcurrency = peakConcurrency.get(),
        maxQueueDelayMs = maxQueueDelayMs.get(),
        totalDispatched = totalDispatched.get(),
    )

    private fun measured(block: Runnable): Runnable {
        val enqueuedNs = nanoTime()
        return Runnable {
            val queueDelayMs = (nanoTime() - enqueuedNs) / NANOS_PER_MILLI
            maxQueueDelayMs.accumulateAndGet(queueDelayMs, ::maxOf)
            val running = active.incrementAndGet()
            peakConcurrency.accumulateAndGet(running, ::maxOf)
            try {
                block.run()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    override fun toString(): String = label

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

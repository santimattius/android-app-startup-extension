package io.github.santimattius.android.startup.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Spec: startup-dispatcher-instrumentation, same wrapped dispatcher reused (atomic under races). */
class DispatcherInstrumentationConcurrencyTest {

    /**
     * Two threads that read the label of a not-yet-wrapped dispatcher at the same time meet at the
     * barrier inside `toString()`. A non-atomic wrap lets both pass and both derive a label, which
     * can burn a `#n` suffix on the winner (and always derives it twice); an atomic wrap derives the label once (the barrier then
     * simply times out for the single caller).
     */
    private class RendezvousDispatcher : CoroutineDispatcher() {
        private val barrier = CyclicBarrier(2)
        val labelReads = AtomicInteger(0)
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun toString(): String {
            labelReads.incrementAndGet()
            try {
                barrier.await(300, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            } catch (_: BrokenBarrierException) {
            }
            return "com.acme.Shared@1f"
        }
    }

    @Test
    fun `concurrent wraps of one shared dispatcher yield one wrapper with the plain label`() {
        val threads = 8
        val instrumentation = DispatcherInstrumentation()
        val shared = RendezvousDispatcher()
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = List(threads) {
                pool.submit<InstrumentedDispatcher> {
                    start.await()
                    instrumentation.wrap(shared)
                }
            }
            start.countDown()
            val wrappers = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, wrappers.toSet().size)
            assertTrue(wrappers.all { it.label == "Shared" })
            assertEquals(setOf("Shared"), instrumentation.snapshots().keys)
            assertEquals("the label must be derived exactly once", 1, shared.labelReads.get())
        } finally {
            pool.shutdownNow()
        }
    }
}

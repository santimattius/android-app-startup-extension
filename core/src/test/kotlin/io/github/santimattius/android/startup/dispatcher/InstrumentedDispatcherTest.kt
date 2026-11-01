package io.github.santimattius.android.startup.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Spec: startup-dispatcher-instrumentation, per-dispatcher snapshot. Pure JVM, no Android. */
class InstrumentedDispatcherTest {

    private val pool = Executors.newFixedThreadPool(2)

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    @Test
    fun `counters under load - total, peak bounded by parallelism`() {
        val dispatcher = InstrumentedDispatcher(pool.asCoroutineDispatcher(), "pool")
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val done = CountDownLatch(4)

        repeat(4) {
            dispatcher.dispatch(EmptyCoroutineContext) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                done.countDown()
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        val snapshot = dispatcher.snapshot()
        assertEquals("pool", snapshot.label)
        assertEquals(4L, snapshot.totalDispatched)
        assertEquals(2, snapshot.peakConcurrency)
    }

    @Test
    fun `maxQueueDelayMs measures enqueue to run start with an injected clock`() {
        val clockNs = AtomicLong(0)
        val queue = ArrayDeque<Runnable>()
        val manual = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.addLast(block)
            }
        }
        val dispatcher = InstrumentedDispatcher(manual, "manual", nanoTime = clockNs::get)

        dispatcher.dispatch(EmptyCoroutineContext) {}
        clockNs.addAndGet(7_000_000L) // 7 ms waiting in the queue
        queue.removeFirst().run()

        assertEquals(7L, dispatcher.snapshot().maxQueueDelayMs)
    }

    @Test
    fun `failing block decrements the active count in finally`() {
        val clockNs = AtomicLong(0)
        val queue = ArrayDeque<Runnable>()
        val manual = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.addLast(block)
            }
        }
        val dispatcher = InstrumentedDispatcher(manual, "manual", nanoTime = clockNs::get)

        dispatcher.dispatch(EmptyCoroutineContext) { error("boom") }
        runCatching { queue.removeFirst().run() }
        dispatcher.dispatch(EmptyCoroutineContext) {}
        queue.removeFirst().run()

        assertEquals("a failure must not leak an active slot", 1, dispatcher.snapshot().peakConcurrency)
        assertEquals(2L, dispatcher.snapshot().totalDispatched)
    }

    @Test
    fun `isDispatchNeeded is delegated`() {
        val never = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = false
            override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        }
        assertEquals(false, InstrumentedDispatcher(never, "never").isDispatchNeeded(EmptyCoroutineContext))
    }

    @Test
    fun `registry reuses one entry per effective dispatcher and keys by label`() {
        val instrumentation = DispatcherInstrumentation()
        val shared = pool.asCoroutineDispatcher()

        val a = instrumentation.wrap(shared)
        val b = instrumentation.wrap(shared)
        val other = instrumentation.wrap(Executors.newSingleThreadExecutor().also { it.shutdown() }.asCoroutineDispatcher())

        assertSame(a, b)
        assertNotSame(a, other)
        assertEquals(2, instrumentation.snapshots().size)
        assertEquals(setOf(a.label, other.label), instrumentation.snapshots().keys)
        assertEquals("ThreadPoolExecutor", a.label)
        assertEquals("Executors\$FinalizableDelegatedExecutorService", other.label)
    }

    @Test
    fun `wrapping never touches global dispatchers`() {
        val before = Dispatchers.IO
        DispatcherInstrumentation().wrap(Dispatchers.IO)
        assertSame(before, Dispatchers.IO)
        assertTrue(Dispatchers.IO !is InstrumentedDispatcher)
    }
}

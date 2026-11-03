package io.github.santimattius.android.startup.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
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
import java.util.concurrent.atomic.AtomicReference
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

    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun `dispatchYield is delegated to dispatchYield and counted and measured`() {
        val clockNs = AtomicLong(0)
        val yielded = ArrayDeque<Runnable>()
        var plainDispatches = 0
        val recording = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                plainDispatches++
            }

            @InternalCoroutinesApi
            override fun dispatchYield(context: CoroutineContext, block: Runnable) {
                yielded.addLast(block)
            }
        }
        val dispatcher = InstrumentedDispatcher(recording, "recording", nanoTime = clockNs::get)
        var ran = false

        dispatcher.dispatchYield(EmptyCoroutineContext) { ran = true }
        clockNs.addAndGet(3_000_000L)
        assertEquals(1, yielded.size)
        yielded.removeFirst().run()

        assertTrue(ran)
        assertEquals("yield must not fall back to dispatch()", 0, plainDispatches)
        assertEquals(1L, dispatcher.snapshot().totalDispatched)
        assertEquals(3L, dispatcher.snapshot().maxQueueDelayMs)
    }

    @Test
    fun `maxQueueDelayMs is positive when a block queues behind a held block on a real single thread`() {
        val single = Executors.newSingleThreadExecutor()
        try {
            val clockNs = AtomicLong(0)
            val dispatcher = InstrumentedDispatcher(single.asCoroutineDispatcher(), "single", nanoTime = clockNs::get)
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val bothDone = CountDownLatch(2)

            dispatcher.dispatch(EmptyCoroutineContext) {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                bothDone.countDown()
            }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            dispatcher.dispatch(EmptyCoroutineContext) { bothDone.countDown() } // queued behind the held first
            clockNs.addAndGet(9_000_000L) // 9 ms pass while the second block waits in the pool queue
            releaseFirst.countDown()
            assertTrue(bothDone.await(5, TimeUnit.SECONDS))

            val snapshot = dispatcher.snapshot()
            assertEquals(9L, snapshot.maxQueueDelayMs)
            assertEquals(1, snapshot.peakConcurrency)
            assertEquals(2L, snapshot.totalDispatched)
        } finally {
            single.shutdownNow()
        }
    }

    @Test
    fun `wrapping a global dispatcher leaves it untouched and only measures dispatches through the wrapper`() {
        val before = Dispatchers.IO
        val beforeText = before.toString()
        val instrumentation = DispatcherInstrumentation()

        val wrapper = instrumentation.wrap(Dispatchers.IO)

        assertSame(before, Dispatchers.IO)
        assertEquals(beforeText, Dispatchers.IO.toString())
        assertNotSame(Dispatchers.IO, wrapper)
        assertEquals("Dispatchers.IO", wrapper.label)

        // Dispatching straight to the global dispatcher is not observed by the wrapper...
        val direct = CountDownLatch(1)
        Dispatchers.IO.dispatch(EmptyCoroutineContext) { direct.countDown() }
        assertTrue(direct.await(5, TimeUnit.SECONDS))
        assertEquals(0L, instrumentation.snapshots().getValue("Dispatchers.IO").totalDispatched)

        // ...while dispatching through the wrapper reaches the very same global dispatcher.
        val viaWrapper = CountDownLatch(1)
        val threadName = AtomicReference<String>()
        wrapper.dispatch(EmptyCoroutineContext) {
            threadName.set(Thread.currentThread().name)
            viaWrapper.countDown()
        }
        assertTrue(viaWrapper.await(5, TimeUnit.SECONDS))
        assertTrue(threadName.get(), threadName.get().startsWith("DefaultDispatcher-worker"))
        assertEquals(1L, instrumentation.snapshots().getValue("Dispatchers.IO").totalDispatched)
    }
}

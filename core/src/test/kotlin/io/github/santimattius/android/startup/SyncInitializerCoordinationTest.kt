package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class SyncInitializerCoordinationTest {
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        GatedRetainedInitializer.reset()
        FailThenSucceedInitializer.reset()
        GatedTransientInitializer.reset()
        InterruptibleTransientInitializer.reset()
    }

    @After
    fun tearDown() {
        GatedRetainedInitializer.release.countDown()
        FailThenSucceedInitializer.release.countDown()
        GatedTransientInitializer.release.countDown()
        InterruptibleTransientInitializer.release.countDown()
        AppStartupInitializer.configure { }
    }

    @Test
    fun `same retained component is created once and shared by concurrent callers`() {
        val initializer = AppStartupInitializer(context)
        val executor = newExecutor("retained")
        val secondCaller = AtomicReference<Thread>()

        try {
            val first = executor.submit<Instance> { initializer.initializeComponent(GatedRetainedInitializer::class.java) }
            assertTrue(GatedRetainedInitializer.entered.await(5, TimeUnit.SECONDS))

            val second =
                executor.submit<Instance> {
                    secondCaller.set(Thread.currentThread())
                    GatedRetainedInitializer.secondRequested.countDown()
                    initializer.initializeComponent(GatedRetainedInitializer::class.java)
                }
            assertTrue(GatedRetainedInitializer.secondRequested.await(5, TimeUnit.SECONDS))
            awaitOwnershipWait(secondCaller.get())
            GatedRetainedInitializer.release.countDown()

            val firstResult = first.get(5, TimeUnit.SECONDS)
            val secondResult = second.get(5, TimeUnit.SECONDS)
            assertEquals(1, GatedRetainedInitializer.createCount.get())
            assertSame(firstResult, secondResult)
        } finally {
            GatedRetainedInitializer.release.countDown()
            shutdown(executor)
        }
    }

    @Test
    fun `waiting caller retries after the first same-component attempt fails`() {
        val initializer = AppStartupInitializer(context)
        val executor = newExecutor("retry")
        val secondCaller = AtomicReference<Thread>()

        try {
            val first = executor.submit<Instance> { initializer.initializeComponent(FailThenSucceedInitializer::class.java) }
            assertTrue(FailThenSucceedInitializer.entered.await(5, TimeUnit.SECONDS))

            val second =
                executor.submit<Instance> {
                    secondCaller.set(Thread.currentThread())
                    FailThenSucceedInitializer.secondRequested.countDown()
                    initializer.initializeComponent(FailThenSucceedInitializer::class.java)
                }
            assertTrue(FailThenSucceedInitializer.secondRequested.await(5, TimeUnit.SECONDS))
            awaitOwnershipWait(secondCaller.get())
            FailThenSucceedInitializer.release.countDown()

            val failure = runCatching { first.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue(failure is ExecutionException)
            assertTrue(failure?.cause is StartupExtensionException)
            assertTrue(failure?.cause?.cause is IllegalStateException)

            assertEquals(Instance(2), second.get(5, TimeUnit.SECONDS))
            assertEquals(2, FailThenSucceedInitializer.createCount.get())
            assertSame(
                second.get(5, TimeUnit.SECONDS),
                initializer.initializeComponent<Instance>(FailThenSucceedInitializer::class.java),
            )
        } finally {
            FailThenSucceedInitializer.release.countDown()
            shutdown(executor)
        }
    }

    @Test
    fun `waiting transient callers each create an unshared result after serialized ownership`() {
        val initializer = AppStartupInitializer(context)
        val executor = newExecutor("transient")
        val secondCaller = AtomicReference<Thread>()

        try {
            val first = executor.submit<Instance> { initializer.initializeComponent(GatedTransientInitializer::class.java) }
            assertTrue(GatedTransientInitializer.entered.await(5, TimeUnit.SECONDS))

            val second =
                executor.submit<Instance> {
                    secondCaller.set(Thread.currentThread())
                    GatedTransientInitializer.secondRequested.countDown()
                    initializer.initializeComponent(GatedTransientInitializer::class.java)
                }
            assertTrue(GatedTransientInitializer.secondRequested.await(5, TimeUnit.SECONDS))
            awaitOwnershipWait(secondCaller.get())
            GatedTransientInitializer.release.countDown()

            assertEquals(Instance(1), first.get(5, TimeUnit.SECONDS))
            assertEquals(Instance(2), second.get(5, TimeUnit.SECONDS))
            assertEquals(2, GatedTransientInitializer.createCount.get())
            assertEquals(
                Instance(3),
                initializer.initializeComponent<Instance>(GatedTransientInitializer::class.java),
            )
            assertEquals(3, GatedTransientInitializer.createCount.get())
        } finally {
            GatedTransientInitializer.release.countDown()
            shutdown(executor)
        }
    }

    @Test
    fun `interrupted same-component waiter restores interruption and leaves coordinator reusable`() {
        val initializer = AppStartupInitializer(context)
        val executor = newExecutor("interrupt")
        val waiterThread = AtomicReference<Thread>()
        val waiterInterrupted = AtomicReference(false)

        try {
            val owner = executor.submit<Instance> { initializer.initializeComponent(InterruptibleTransientInitializer::class.java) }
            assertTrue(InterruptibleTransientInitializer.entered.await(5, TimeUnit.SECONDS))

            val waiter =
                executor.submit<Instance> {
                    waiterThread.set(Thread.currentThread())
                    InterruptibleTransientInitializer.secondRequested.countDown()
                    try {
                        initializer.initializeComponent(InterruptibleTransientInitializer::class.java)
                    } finally {
                        waiterInterrupted.set(Thread.currentThread().isInterrupted)
                    }
                }
            assertTrue(InterruptibleTransientInitializer.secondRequested.await(5, TimeUnit.SECONDS))
            interruptWhenWaiting(waiterThread.get())

            val failure = runCatching { waiter.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue(failure is ExecutionException)
            assertTrue(failure?.cause is IllegalStateException)
            assertTrue(failure?.cause?.cause is InterruptedException)
            assertTrue("waiter must restore its interrupt flag", waiterInterrupted.get())

            InterruptibleTransientInitializer.release.countDown()
            assertEquals(Instance(1), owner.get(5, TimeUnit.SECONDS))
            assertEquals(
                Instance(2),
                initializer.initializeComponent<Instance>(InterruptibleTransientInitializer::class.java),
            )
            assertEquals(2, InterruptibleTransientInitializer.createCount.get())
        } finally {
            InterruptibleTransientInitializer.release.countDown()
            shutdown(executor)
        }
    }

    private fun interruptWhenWaiting(thread: Thread) {
        awaitOwnershipWait(thread)
        thread.interrupt()
    }

    private fun awaitOwnershipWait(thread: Thread) {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadlineNs) {
            Thread.yield()
        }
        assertEquals("caller must be blocked on same-component ownership", Thread.State.WAITING, thread.state)
    }

    private fun newExecutor(name: String): ExecutorService =
        Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "sync-coordination-$name") }

    private fun shutdown(executor: ExecutorService) {
        executor.shutdownNow()
        assertTrue("executor did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    data class Instance(
        val attempt: Int,
    )

    class GatedRetainedInitializer : StartupSyncInitializer<Instance> {
        override fun create(context: Context): Instance {
            val attempt = createCount.incrementAndGet()
            if (attempt == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            return Instance(attempt)
        }

        companion object {
            val createCount = AtomicInteger()
            lateinit var entered: CountDownLatch
            lateinit var secondRequested: CountDownLatch
            lateinit var release: CountDownLatch

            fun reset() {
                createCount.set(0)
                entered = CountDownLatch(1)
                secondRequested = CountDownLatch(1)
                release = CountDownLatch(1)
            }
        }
    }

    class FailThenSucceedInitializer : StartupSyncInitializer<Instance> {
        override fun create(context: Context): Instance {
            val attempt = createCount.incrementAndGet()
            if (attempt == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                error("first attempt fails")
            }
            return Instance(attempt)
        }

        companion object {
            val createCount = AtomicInteger()
            lateinit var entered: CountDownLatch
            lateinit var secondRequested: CountDownLatch
            lateinit var release: CountDownLatch

            fun reset() {
                createCount.set(0)
                entered = CountDownLatch(1)
                secondRequested = CountDownLatch(1)
                release = CountDownLatch(1)
            }
        }
    }

    class GatedTransientInitializer : StartupSyncInitializer<Instance> {
        override fun create(context: Context): Instance {
            val attempt = createCount.incrementAndGet()
            if (attempt == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            return Instance(attempt)
        }

        override fun retainAfterStartup() = false

        companion object {
            val createCount = AtomicInteger()
            lateinit var entered: CountDownLatch
            lateinit var secondRequested: CountDownLatch
            lateinit var release: CountDownLatch

            fun reset() {
                createCount.set(0)
                entered = CountDownLatch(1)
                secondRequested = CountDownLatch(1)
                release = CountDownLatch(1)
            }
        }
    }

    class InterruptibleTransientInitializer : StartupSyncInitializer<Instance> {
        override fun create(context: Context): Instance {
            val attempt = createCount.incrementAndGet()
            if (attempt == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            return Instance(attempt)
        }

        override fun retainAfterStartup() = false

        companion object {
            val createCount = AtomicInteger()
            lateinit var entered: CountDownLatch
            lateinit var secondRequested: CountDownLatch
            lateinit var release: CountDownLatch

            fun reset() {
                createCount.set(0)
                entered = CountDownLatch(1)
                secondRequested = CountDownLatch(1)
                release = CountDownLatch(1)
            }
        }
    }
}

package io.github.santimattius.android.startup.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Spec: startup-dispatcher-instrumentation, derived and sanitized label. Pure JVM. */
class DispatcherLabelTest {

    private val pool = Executors.newFixedThreadPool(2)

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun fake(text: String) = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun toString(): String = text
    }

    @Test
    fun `identity hash is stripped and the package prefix is dropped`() {
        assertEquals("MyPool", deriveDispatcherLabel(fake("com.acme.startup.MyPool@1a2b3c")))
    }

    @Test
    fun `bracketed pool statistics are stripped`() {
        val text = "java.util.concurrent.ThreadPoolExecutor@7852e922" +
            "[Running, pool size = 2, active threads = 0, queued tasks = 0, completed tasks = 0]"
        assertEquals("ThreadPoolExecutor", deriveDispatcherLabel(fake(text)))
    }

    @Test
    fun `readable global dispatcher names are kept as is`() {
        assertEquals("Dispatchers.Default", deriveDispatcherLabel(Dispatchers.Default))
        assertEquals("Dispatchers.IO", deriveDispatcherLabel(Dispatchers.IO))
        assertEquals(
            "Dispatchers.IO.limitedParallelism(2)",
            deriveDispatcherLabel(fake("Dispatchers.IO.limitedParallelism(2)")),
        )
    }

    @Test
    fun `blank text falls back to the class name`() {
        assertEquals("DispatcherLabelTest\$fake\$1", deriveDispatcherLabel(fake("@abc[x]")))
    }

    @Test
    fun `label of a real executor dispatcher is stable while its statistics change`() {
        val dispatcher = pool.asCoroutineDispatcher()
        val before = deriveDispatcherLabel(dispatcher)
        val done = CountDownLatch(2)
        repeat(2) { pool.execute { done.countDown() } }
        assertEquals(true, done.await(5, TimeUnit.SECONDS))
        val after = deriveDispatcherLabel(dispatcher)

        assertEquals("ThreadPoolExecutor", before)
        assertEquals(before, after)
    }

    @Test
    fun `two distinct dispatchers with the same derived label get a collision suffix`() {
        val instrumentation = DispatcherInstrumentation()
        val first = instrumentation.wrap(fake("com.a.Shared@1"))
        val second = instrumentation.wrap(fake("com.b.Shared@2"))

        assertEquals("Shared", first.label)
        assertEquals("Shared#2", second.label)
        assertEquals(setOf("Shared", "Shared#2"), instrumentation.snapshots().keys)
    }
}

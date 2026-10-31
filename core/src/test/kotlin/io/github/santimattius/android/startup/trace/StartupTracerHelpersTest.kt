package io.github.santimattius.android.startup.trace

import io.github.santimattius.android.startup.trace.RecordingStartupTracer.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTracerHelpersTest {

    private val tracer = RecordingStartupTracer()

    @Test
    fun `disabled tracer never evaluates the name lambda nor records calls`() = runBlocking {
        tracer.enabled = false
        var evaluated = 0
        val name = { evaluated++; "n" }

        val sync = tracer.section(name) { 1 }
        val async = tracer.asyncSection(name) { 2 }
        val opened = tracer.openSection(name)
        tracer.closeSection(opened)
        val span = tracer.openAsyncSection(name)
        tracer.closeAsyncSection(span)

        assertEquals(3, sync + async)
        assertEquals(0, evaluated)
        assertTrue(tracer.events.isEmpty())
    }

    @Test
    fun `section ends in finally when the block throws`() {
        runCatching { tracer.section({ "boom" }) { error("x") } }

        assertTrue(tracer.syncBalanced())
        assertEquals(2, tracer.events.size)
    }

    @Test
    fun `async section ends when the block throws or is cancelled`() = runBlocking {
        runCatching { tracer.asyncSection({ "fail" }) { error("x") } }
        val gate = CompletableDeferred<Unit>()
        val job = launch { tracer.asyncSection({ "cancelled" }) { gate.await() } }
        while (tracer.asyncBeginIndex("cancelled") < 0) delay(1)
        job.cancel(CancellationException("stop"))
        job.join()

        assertTrue(tracer.asyncBalanced())
        assertTrue(tracer.asyncEndIndex("fail") >= 0)
        assertTrue(tracer.asyncEndIndex("cancelled") >= 0)
    }

    @Test
    fun `async cookie captured at begin pairs across a dispatcher hop`() = runBlocking {
        tracer.asyncSection({ "hop" }) {
            withContext(Dispatchers.Default) { delay(5) }
        }

        val begin = tracer.events.filterIsInstance<Event.AsyncBegin>().single()
        val end = tracer.events.filterIsInstance<Event.AsyncEnd>().single()
        assertEquals(begin.cookie, end.cookie)
        assertEquals(begin.name, end.name)
    }

    @Test
    fun `concurrent same-name sections get distinct cookies`() = runBlocking {
        List(8) { async(Dispatchers.Default) { tracer.asyncSection({ "same" }) { delay(10) } } }.awaitAll()

        val cookies = tracer.events.filterIsInstance<Event.AsyncBegin>().map { it.cookie }
        assertEquals(8, cookies.toSet().size)
        assertTrue(tracer.asyncBalanced())
    }

    @Test
    fun `open and close helpers pair like the inline sections`() {
        val opened = tracer.openSection { "node" }
        tracer.closeSection(opened)

        assertTrue(opened)
        assertFalse(tracer.events.isEmpty())
        assertTrue(tracer.syncBalanced())
    }
}

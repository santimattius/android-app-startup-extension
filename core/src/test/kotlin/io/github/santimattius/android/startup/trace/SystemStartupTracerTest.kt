package io.github.santimattius.android.startup.trace

import android.os.Build
import android.os.Trace
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises the real [SystemStartupTracer]. `android.os.Trace` is mocked statically so the
 * platform calls (otherwise invisible without a Perfetto session) become observable.
 */
@RunWith(RobolectricTestRunner::class)
class SystemStartupTracerTest {

    @After
    fun tearDown() {
        unmockkStatic(Trace::class)
    }

    // Trace.isEnabled/beginAsyncSection/endAsyncSection/setCounter do not exist in the API 24
    // android-all jar, so they are only stubbed at 29+. On 24 any call to them would surface as
    // NoSuchMethodError, which makes "never invoked" a stronger assertion than a verify count.
    private fun mockTrace(enabled: Boolean) {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        every { Trace.isEnabled() } returns enabled
        every { Trace.beginAsyncSection(any(), any()) } returns Unit
        every { Trace.endAsyncSection(any(), any()) } returns Unit
        every { Trace.setCounter(any(), any()) } returns Unit
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun `below API 29 async and counter calls are no-ops that never throw and sync stays on`() {
        mockTrace(enabled = true)

        assertTrue(SystemStartupTracer.isEnabled) // legacy: sync sections always on
        assertFalse(SystemStartupTracer.isAsyncEnabled)

        SystemStartupTracer.beginAsyncSection("a", 1)
        SystemStartupTracer.endAsyncSection("a", 1)
        SystemStartupTracer.setCounter("c", 3L)
        SystemStartupTracer.beginSection("s")
        SystemStartupTracer.endSection()

        verify(exactly = 1) { Trace.beginSection("s") }
        verify(exactly = 1) { Trace.endSection() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun `below API 29 the open helpers yield no async span and a sync section`() {
        mockTrace(enabled = false)

        assertEquals(null, SystemStartupTracer.openAsyncSection { "x" })
        assertTrue(SystemStartupTracer.openSection { "x" })
        SystemStartupTracer.closeSection(true)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `on API 29 async and counter calls are forwarded to the platform`() {
        mockTrace(enabled = true)

        assertTrue(SystemStartupTracer.isEnabled)
        assertTrue(SystemStartupTracer.isAsyncEnabled)

        SystemStartupTracer.beginAsyncSection("a", 7)
        SystemStartupTracer.endAsyncSection("a", 7)
        SystemStartupTracer.setCounter("c", 3L)

        verify(exactly = 1) { Trace.beginAsyncSection("a", 7) }
        verify(exactly = 1) { Trace.endAsyncSection("a", 7) }
        verify(exactly = 1) { Trace.setCounter("c", 3L) }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `on API 29 enabled flags follow Trace isEnabled`() {
        mockTrace(enabled = false)
        assertFalse(SystemStartupTracer.isEnabled)
        assertFalse(SystemStartupTracer.isAsyncEnabled)
        assertEquals(null, SystemStartupTracer.openAsyncSection { "x" })
        assertFalse(SystemStartupTracer.openSection { "x" })

        every { Trace.isEnabled() } returns true
        assertTrue(SystemStartupTracer.isEnabled)
        assertTrue(SystemStartupTracer.isAsyncEnabled)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `marker is a zero-length sync slice when enabled and nothing when disabled`() {
        mockTrace(enabled = true)
        SystemStartupTracer.marker("m")
        verify(exactly = 1) { Trace.beginSection("m") }
        verify(exactly = 1) { Trace.endSection() }

        mockTrace(enabled = false)
        SystemStartupTracer.marker("m2")
        verify(exactly = 0) { Trace.beginSection("m2") }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun `marker below API 29 still emits a slice`() {
        mockTrace(enabled = false)
        SystemStartupTracer.marker("m")
        verify(exactly = 1) { Trace.beginSection("m") }
        verify(exactly = 1) { Trace.endSection() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `nextCookie is unique across concurrent callers`() {
        val threads = 8
        val perThread = 500
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val all = (1..threads).map {
                pool.submit<List<Int>> {
                    barrier.await()
                    List(perThread) { SystemStartupTracer.nextCookie() }
                }
            }.flatMap { it.get(10, TimeUnit.SECONDS) }

            assertEquals(threads * perThread, all.size)
            assertEquals(threads * perThread, all.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `concurrent same-name async sections get distinct cookies`() {
        mockTrace(enabled = true)

        val spans = List(50) { SystemStartupTracer.openAsyncSection { "Startup::create::X" } }

        assertEquals(50, spans.map { it!!.cookie }.toSet().size)
        spans.forEach { SystemStartupTracer.closeAsyncSection(it) }
        verify(exactly = 50) { Trace.endAsyncSection("Startup::create::X", any()) }
    }
}

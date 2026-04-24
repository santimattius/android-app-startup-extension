package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Performance characteristics of [SyncOrderingStrategy.Topological] and
 * [AsyncInitializerStrategy.Validated].
 *
 * These tests do NOT assert sub-millisecond speed. Instead they verify two
 * properties that protect against algorithmic regressions:
 *
 * 1. **Bounded time** — sorting / validating an 8-node graph completes within
 *    a generous wall-clock threshold. A correct O(V+E) implementation should
 *    finish in single-digit milliseconds; a buggy O(2^N) explosion would blow
 *    past even 1 000 ms for N=8.
 *
 * 2. **Operational reduction** — [AsyncInitializerStrategy.Validated] launches
 *    only root coroutines. The tests verify that for a chain of N initializers
 *    exactly 1 coroutine is launched (not N), and for isolated nodes all N
 *    coroutines are launched (they are all roots).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class StartupStrategyPerformanceTest {

    private lateinit var initializer: AppStartupInitializer
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        initializer = AppStartupInitializer(context)
    }

    // ── Sync: topologicalSort bounded time ────────────────────────────────────

    @Test
    fun `topologicalSort on 8-node chain completes within time bound`() {
        val graph = setOf(
            S1::class.java, S2::class.java, S3::class.java, S4::class.java,
            S5::class.java, S6::class.java, S7::class.java, S8::class.java,
        )

        val elapsed = measureMs { initializer.topologicalSort(graph) }

        assertTrue(
            "topologicalSort took ${elapsed}ms — expected < ${SORT_THRESHOLD_MS}ms",
            elapsed < SORT_THRESHOLD_MS,
        )
    }

    @Test
    fun `topologicalSort on 8-node chain returns all nodes in valid dependency order`() {
        val graph = setOf(
            S1::class.java, S2::class.java, S3::class.java, S4::class.java,
            S5::class.java, S6::class.java, S7::class.java, S8::class.java,
        )

        val sorted = initializer.topologicalSort(graph)

        assertEquals("All 8 nodes must be present in the result", 8, sorted.size)
        // S1 has no deps so it must always appear before S2, S3…S8
        assertTrue("S1 must precede S2", sorted.indexOf(S1::class.java) < sorted.indexOf(S2::class.java))
        assertTrue("S4 must precede S5", sorted.indexOf(S4::class.java) < sorted.indexOf(S5::class.java))
        assertTrue("S7 must precede S8", sorted.indexOf(S7::class.java) < sorted.indexOf(S8::class.java))
    }

    // ── Async: asyncValidateAndGetRoots bounded time ──────────────────────────

    @Test
    fun `asyncValidateAndGetRoots on 8-node chain completes within time bound`() {
        val graph = setOf(
            A1::class.java, A2::class.java, A3::class.java, A4::class.java,
            A5::class.java, A6::class.java, A7::class.java, A8::class.java,
        )

        val elapsed = measureMs { initializer.asyncValidateAndGetRoots(graph) }

        assertTrue(
            "asyncValidateAndGetRoots took ${elapsed}ms — expected < ${SORT_THRESHOLD_MS}ms",
            elapsed < SORT_THRESHOLD_MS,
        )
    }

    // ── Async: operational reduction ──────────────────────────────────────────

    @Test
    fun `Validated - 8-node chain reduces launched coroutines from 8 to 1`() {
        val graph = setOf(
            A1::class.java, A2::class.java, A3::class.java, A4::class.java,
            A5::class.java, A6::class.java, A7::class.java, A8::class.java,
        )

        val roots = initializer.asyncValidateAndGetRoots(graph)

        assertEquals(
            "A linear chain of 8 has only 1 root — Validated must launch 1 coroutine instead of 8",
            1,
            roots.size,
        )
    }

    @Test
    fun `Validated - 8 isolated nodes launch all 8 coroutines (all are roots)`() {
        val graph = setOf(
            I1::class.java, I2::class.java, I3::class.java, I4::class.java,
            I5::class.java, I6::class.java, I7::class.java, I8::class.java,
        )

        val roots = initializer.asyncValidateAndGetRoots(graph)

        assertEquals(
            "Isolated nodes have no dependents — every node is a root",
            8,
            roots.size,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private inline fun measureMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }

    // ── Sync chain fixtures: S1 ← S2 ← … ← S8 ───────────────────────────────

    private class S1 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s1"
    }

    private class S2 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s2"
        override fun dependencies() = listOf(S1::class.java)
    }

    private class S3 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s3"
        override fun dependencies() = listOf(S2::class.java)
    }

    private class S4 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s4"
        override fun dependencies() = listOf(S3::class.java)
    }

    private class S5 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s5"
        override fun dependencies() = listOf(S4::class.java)
    }

    private class S6 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s6"
        override fun dependencies() = listOf(S5::class.java)
    }

    private class S7 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s7"
        override fun dependencies() = listOf(S6::class.java)
    }

    private class S8 : StartupSyncInitializer<String> {
        override fun create(context: Context) = "s8"
        override fun dependencies() = listOf(S7::class.java)
    }

    // ── Async chain fixtures: A1 ← A2 ← … ← A8 ──────────────────────────────

    private class A1 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a1"
    }

    private class A2 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a2"
        override fun dependencies() = listOf(A1::class.java)
    }

    private class A3 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a3"
        override fun dependencies() = listOf(A2::class.java)
    }

    private class A4 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a4"
        override fun dependencies() = listOf(A3::class.java)
    }

    private class A5 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a5"
        override fun dependencies() = listOf(A4::class.java)
    }

    private class A6 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a6"
        override fun dependencies() = listOf(A5::class.java)
    }

    private class A7 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a7"
        override fun dependencies() = listOf(A6::class.java)
    }

    private class A8 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a8"
        override fun dependencies() = listOf(A7::class.java)
    }

    // ── Isolated async fixtures: I1..I8, no dependencies ─────────────────────

    private class I1 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i1" }
    private class I2 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i2" }
    private class I3 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i3" }
    private class I4 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i4" }
    private class I5 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i5" }
    private class I6 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i6" }
    private class I7 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i7" }
    private class I8 : StartupAsyncInitializer<String> { override suspend fun create(context: Context) = "i8" }

    companion object {
        private const val SORT_THRESHOLD_MS = 1_000L
    }
}

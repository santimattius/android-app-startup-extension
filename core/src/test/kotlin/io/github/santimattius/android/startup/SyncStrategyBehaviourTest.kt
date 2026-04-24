package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Behavioural tests for [SyncOrderingStrategy].
 *
 * Verifies observable end-to-end properties of both strategies rather than
 * internal algorithm details:
 *
 * - [SyncOrderingStrategy.Topological]: execution order always matches the
 *   declared dependency order, and shared nodes are created exactly once.
 * - [SyncOrderingStrategy.Lazy]: DFS recursion produces the same correct
 *   outcomes even when the discovery order is "wrong" (dependents appear
 *   before their dependencies).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class SyncStrategyBehaviourTest {

    private lateinit var initializer: AppStartupInitializer
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        initializer = AppStartupInitializer(context)
        executionOrder.clear()
        createCounts.clear()
    }

    // ── Topological: execution order ──────────────────────────────────────────

    @Test
    fun `Topological - chain executes in strict dependency order`() {
        val sorted = initializer.topologicalSort(
            setOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)
        )
        sorted.forEach { initializer.doInitialize<Any>(it) }

        assertEquals(
            "Expected C → B → A but got $executionOrder",
            listOf("C", "B", "A"),
            executionOrder.toList(),
        )
    }

    @Test
    fun `Topological - dependent has access to its dependency result at runtime`() {
        val sorted = initializer.topologicalSort(
            setOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)
        )
        sorted.forEach { initializer.doInitialize<Any>(it) }

        val resultA = initializer.initializeComponent(ChainA::class.java)
        assertEquals("a", resultA)
    }

    // ── Topological: diamond — shared dep created exactly once ────────────────

    @Test
    fun `Topological - shared dependency in diamond graph initialized exactly once`() {
        val sorted = initializer.topologicalSort(
            setOf(DiamondA::class.java, DiamondB::class.java, DiamondC::class.java, DiamondD::class.java)
        )
        sorted.forEach { initializer.doInitialize<Any>(it) }

        assertEquals(
            "D is reached via B and via C but must be created only once",
            1,
            createCounts["D"],
        )
        assertEquals(1, createCounts["B"])
        assertEquals(1, createCounts["C"])
        assertEquals(1, createCounts["A"])
    }

    // ── Lazy: DFS resolves correctly despite reverse discovery order ──────────

    @Test
    fun `Lazy - chain resolves correctly when discovery order is reverse dependency order`() {
        // Simulate Bundle returning initializers in "wrong" order: A first, then B, then C.
        // Lazy DFS inside doInitialize must still produce the correct execution order.
        listOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)
            .forEach { initializer.doInitialize<Any>(it) }

        assertEquals(
            "DFS must resolve C then B then A even though A was called first",
            listOf("C", "B", "A"),
            executionOrder.toList(),
        )
    }

    @Test
    fun `Lazy - shared dependency in diamond graph initialized exactly once`() {
        // Call A first: its DFS resolves B, C, and D. Subsequent calls for B, C, D are no-ops.
        listOf(DiamondA::class.java, DiamondB::class.java, DiamondC::class.java, DiamondD::class.java)
            .forEach { initializer.doInitialize<Any>(it) }

        assertEquals(
            "D is resolved by A's DFS chain; later calls for D must be no-ops",
            1,
            createCounts["D"],
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    companion object {
        val executionOrder: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val createCounts: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

        private fun record(name: String) {
            executionOrder.add(name)
            createCounts.merge(name, 1, Int::plus)
        }
    }

    // Chain: C (no deps) → B → A
    private class ChainC : StartupSyncInitializer<String> {
        override fun create(context: Context): String { record("C"); return "c" }
    }

    private class ChainB : StartupSyncInitializer<String> {
        override fun create(context: Context): String { record("B"); return "b" }
        override fun dependencies() = listOf(ChainC::class.java)
    }

    private class ChainA : StartupSyncInitializer<String> {
        override fun create(context: Context): String { record("A"); return "a" }
        override fun dependencies() = listOf(ChainB::class.java)
    }

    // Diamond: D (no deps), B → D, C → D, A → B + C
    private class DiamondD : StartupSyncInitializer<String> {
        override fun create(context: Context): String { record("D"); return "d" }
    }

    private class DiamondB : StartupSyncInitializer<String> {
        override fun create(context: Context): String { record("B"); return "b" }
        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondC : StartupSyncInitializer<String> {
        override fun create(context: Context): String { record("C"); return "c" }
        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondA : StartupSyncInitializer<String> {
        override fun create(context: Context): String { record("A"); return "a" }
        override fun dependencies() = listOf(DiamondB::class.java, DiamondC::class.java)
    }
}

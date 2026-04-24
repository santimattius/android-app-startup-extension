package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [AppStartupInitializer.topologicalSort] (Kahn's algorithm):
 * - correct ordering (dependencies before dependents)
 * - diamond graph (shared dependency, two paths)
 * - cycle detection fires before any create() is called
 * - isolated nodes (no dependencies) are included in the result
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class SyncOrderingStrategyTest {

    private val context: Context = mockk(relaxed = true)
    private val initializer = AppStartupInitializer(context)

    @Before
    fun resetCreateCount() {
        createCallCount = 0
    }

    // ── Chain: C → B → A ─────────────────────────────────────────────────────

    @Test
    fun `topologicalSort places dependency before dependent in a chain`() {
        val result = initializer.topologicalSort(
            setOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)
        )

        assertTrue("C must come before B", result.indexOf(ChainC::class.java) < result.indexOf(ChainB::class.java))
        assertTrue("B must come before A", result.indexOf(ChainB::class.java) < result.indexOf(ChainA::class.java))
    }

    // ── Diamond: D → B, D → C, B+C → A ──────────────────────────────────────

    @Test
    fun `topologicalSort handles diamond dependency graph`() {
        val result = initializer.topologicalSort(
            setOf(DiamondA::class.java, DiamondB::class.java, DiamondC::class.java, DiamondD::class.java)
        )

        assertTrue("D before B", result.indexOf(DiamondD::class.java) < result.indexOf(DiamondB::class.java))
        assertTrue("D before C", result.indexOf(DiamondD::class.java) < result.indexOf(DiamondC::class.java))
        assertTrue("B before A", result.indexOf(DiamondB::class.java) < result.indexOf(DiamondA::class.java))
        assertTrue("C before A", result.indexOf(DiamondC::class.java) < result.indexOf(DiamondA::class.java))
    }

    // ── Isolated nodes ────────────────────────────────────────────────────────

    @Test
    fun `topologicalSort includes all isolated nodes`() {
        val result = initializer.topologicalSort(
            setOf(IsolatedA::class.java, IsolatedB::class.java)
        )

        assertEquals(2, result.size)
        assertTrue(IsolatedA::class.java in result)
        assertTrue(IsolatedB::class.java in result)
    }

    // ── Cycle detection ───────────────────────────────────────────────────────

    @Test
    fun `topologicalSort throws before any create() is called when a cycle exists`() {
        assertThrows(StartupExtensionException::class.java) {
            initializer.topologicalSort(setOf(CyclicA::class.java, CyclicB::class.java))
        }

        assertEquals(
            "create() must not be invoked during topological sort — cycle should be detected via dependencies() only",
            0,
            createCallCount,
        )
    }

    @Test
    fun `topologicalSort exception message names the cycling nodes`() {
        val exception = assertThrows(StartupExtensionException::class.java) {
            initializer.topologicalSort(setOf(CyclicA::class.java, CyclicB::class.java))
        }

        val message = exception.message.orEmpty()
        assertTrue(
            "Expected both CyclicA and CyclicB in message, got: $message",
            message.contains("CyclicA") && message.contains("CyclicB"),
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    companion object {
        @Volatile
        var createCallCount = 0
    }

    // Chain: no deps → B depends on C → A depends on B
    private class ChainC : StartupSyncInitializer<String> {
        override fun create(context: Context) = "c"
    }

    private class ChainB : StartupSyncInitializer<String> {
        override fun create(context: Context) = "b"
        override fun dependencies() = listOf(ChainC::class.java)
    }

    private class ChainA : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"
        override fun dependencies() = listOf(ChainB::class.java)
    }

    // Diamond: D has no deps, B+C depend on D, A depends on B and C
    private class DiamondD : StartupSyncInitializer<String> {
        override fun create(context: Context) = "d"
    }

    private class DiamondB : StartupSyncInitializer<String> {
        override fun create(context: Context) = "b"
        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondC : StartupSyncInitializer<String> {
        override fun create(context: Context) = "c"
        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondA : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"
        override fun dependencies() = listOf(DiamondB::class.java, DiamondC::class.java)
    }

    // Isolated: no dependencies between them
    private class IsolatedA : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"
    }

    private class IsolatedB : StartupSyncInitializer<String> {
        override fun create(context: Context) = "b"
    }

    // Cycle: A depends on B, B depends on A
    private class CyclicA : StartupSyncInitializer<String> {
        override fun create(context: Context): String { createCallCount++; return "a" }
        override fun dependencies() = listOf(CyclicB::class.java)
    }

    private class CyclicB : StartupSyncInitializer<String> {
        override fun create(context: Context): String { createCallCount++; return "b" }
        override fun dependencies() = listOf(CyclicA::class.java)
    }
}

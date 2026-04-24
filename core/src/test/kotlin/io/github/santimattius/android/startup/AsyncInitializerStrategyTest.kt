package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
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
 * Verifies [AppStartupInitializer.asyncValidateAndGetRoots] (Kahn's + root extraction):
 * - only root nodes are returned (those no other async initializer depends on)
 * - diamond graphs: shared dependencies are roots only when no other node claims them
 * - isolated nodes (no dependencies) are all roots
 * - cycle detection fires before any create() is called
 * - exception message names all cycle participants
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AsyncInitializerStrategyTest {

    private val context: Context = mockk(relaxed = true)
    private val initializer = AppStartupInitializer(context)

    @Before
    fun resetCreateCount() {
        createCallCount = 0
    }

    // ── Chain: C ← B ← A (A depends on B, B depends on C) ───────────────────

    @Test
    fun `asyncValidateAndGetRoots returns only root in a chain`() {
        val roots = initializer.asyncValidateAndGetRoots(
            setOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)
        )

        assertEquals(setOf(ChainA::class.java), roots)
    }

    // ── Diamond: B+C ← A, D ← B, D ← C ─────────────────────────────────────

    @Test
    fun `asyncValidateAndGetRoots returns single root in a diamond graph`() {
        val roots = initializer.asyncValidateAndGetRoots(
            setOf(DiamondA::class.java, DiamondB::class.java, DiamondC::class.java, DiamondD::class.java)
        )

        assertEquals(setOf(DiamondA::class.java), roots)
    }

    // ── Isolated nodes ────────────────────────────────────────────────────────

    @Test
    fun `asyncValidateAndGetRoots returns all nodes when none depend on each other`() {
        val roots = initializer.asyncValidateAndGetRoots(
            setOf(IsolatedA::class.java, IsolatedB::class.java)
        )

        assertEquals(setOf(IsolatedA::class.java, IsolatedB::class.java), roots)
    }

    // ── Cycle detection ───────────────────────────────────────────────────────

    @Test
    fun `asyncValidateAndGetRoots throws before any create() is called when a cycle exists`() {
        assertThrows(StartupExtensionException::class.java) {
            initializer.asyncValidateAndGetRoots(
                setOf(CyclicA::class.java, CyclicB::class.java)
            )
        }

        assertEquals(
            "create() must not be invoked during graph validation — cycle should be detected via dependencies() only",
            0,
            createCallCount,
        )
    }

    @Test
    fun `asyncValidateAndGetRoots exception message names all cycling nodes`() {
        val exception = assertThrows(StartupExtensionException::class.java) {
            initializer.asyncValidateAndGetRoots(
                setOf(CyclicA::class.java, CyclicB::class.java)
            )
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

    // Chain: C (no deps) ← B ← A
    private class ChainC : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "c"
    }

    private class ChainB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "b"
        override fun dependencies() = listOf(ChainC::class.java)
    }

    private class ChainA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a"
        override fun dependencies() = listOf(ChainB::class.java)
    }

    // Diamond: D (no deps) ← B, D ← C, B+C ← A
    private class DiamondD : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "d"
    }

    private class DiamondB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "b"
        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondC : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "c"
        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a"
        override fun dependencies() = listOf(DiamondB::class.java, DiamondC::class.java)
    }

    // Isolated: no dependencies between them
    private class IsolatedA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a"
    }

    private class IsolatedB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "b"
    }

    // Cycle: A depends on B, B depends on A
    private class CyclicA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String { createCallCount++; return "a" }
        override fun dependencies() = listOf(CyclicB::class.java)
    }

    private class CyclicB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String { createCallCount++; return "b" }
        override fun dependencies() = listOf(CyclicA::class.java)
    }
}

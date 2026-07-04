package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Spec: startup-priority-staggering — "Priority inversion clamp and warn" (ADR-6).
 *
 * [AppStartupInitializer.computeEffectivePriorities] is a pure-ish internal pass over the discovered
 * async graph: when an eager (`NORMAL`/`CRITICAL`) initializer depends transitively on a `DEFERRED`
 * one, the DEFERRED dependency's EFFECTIVE priority is clamped up to its most-eager transitive
 * dependent (min ordinal) and a single warning is emitted. It never rejects a valid graph.
 *
 * These tests assert the clamp result directly (no launches) plus the once-only warning via
 * [ShadowLog]. The inversion warning always surfaces via `Log.w`, independent of
 * `debugLoggingEnabled`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class EffectivePriorityTest {

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        ShadowLog.clear()
        every { context.applicationContext } returns context
    }

    @After
    fun tearDown() {
        AppStartupInitializer.configure { }
    }

    @Test
    fun `clamps a DEFERRED dependency up to its NORMAL dependent`() {
        val initializer = AppStartupInitializer(context)

        val effective = initializer.computeEffectivePriorities(
            setOf(NormalDependsOnDeferred::class.java, DeferredDep::class.java),
        )

        assertEquals(
            "A DEFERRED node an eager NORMAL initializer depends on must be clamped to NORMAL",
            StartupPriority.NORMAL,
            effective[DeferredDep::class.java],
        )
        assertEquals(
            "The eager dependent keeps its declared NORMAL priority",
            StartupPriority.NORMAL,
            effective[NormalDependsOnDeferred::class.java],
        )
    }

    @Test
    fun `clamps to the most-eager dependent when several eager nodes depend on it`() {
        val initializer = AppStartupInitializer(context)

        val effective = initializer.computeEffectivePriorities(
            setOf(
                CriticalDependsOnShared::class.java,
                NormalDependsOnShared::class.java,
                SharedDeferred::class.java,
            ),
        )

        assertEquals(
            "With both a CRITICAL and a NORMAL dependent, the clamp picks the most-eager (min ordinal = CRITICAL)",
            StartupPriority.CRITICAL,
            effective[SharedDeferred::class.java],
        )
    }

    @Test
    fun `a DEFERRED node with no eager dependent keeps DEFERRED`() {
        val initializer = AppStartupInitializer(context)

        val effective = initializer.computeEffectivePriorities(
            setOf(LonelyDeferred::class.java, PlainNormal::class.java),
        )

        assertEquals(
            "A DEFERRED node nothing eager depends on must remain DEFERRED",
            StartupPriority.DEFERRED,
            effective[LonelyDeferred::class.java],
        )
        assertEquals(
            "An unrelated NORMAL node keeps NORMAL",
            StartupPriority.NORMAL,
            effective[PlainNormal::class.java],
        )
    }

    @Test
    fun `reverse-reachability DFS is cycle-safe for self and mutual DEFERRED dependencies`() {
        val initializer = AppStartupInitializer(context)

        val effective = initializer.computeEffectivePriorities(
            setOf(SelfDeferred::class.java, MutualDeferredA::class.java, MutualDeferredB::class.java),
        )

        // Termination is the real assertion (a naive DFS would loop forever); no eager dependent
        // exists in either cycle, so every node keeps its declared DEFERRED priority.
        assertEquals(StartupPriority.DEFERRED, effective[SelfDeferred::class.java])
        assertEquals(StartupPriority.DEFERRED, effective[MutualDeferredA::class.java])
        assertEquals(StartupPriority.DEFERRED, effective[MutualDeferredB::class.java])
    }

    @Test
    fun `emits the inversion warning at most once per initializer`() {
        val initializer = AppStartupInitializer(context)
        val discovered = setOf(NormalDependsOnDeferred::class.java, DeferredDep::class.java)

        initializer.computeEffectivePriorities(discovered)
        initializer.computeEffectivePriorities(discovered)

        assertEquals(
            "The priority-inversion warning must be emitted at most once per startup",
            1,
            inversionWarnings(),
        )
    }

    @Test
    fun `emits the inversion warning even when debugLoggingEnabled is false`() {
        AppStartupInitializer.configure { debugLoggingEnabled = false }
        val initializer = AppStartupInitializer(context)

        initializer.computeEffectivePriorities(
            setOf(NormalDependsOnDeferred::class.java, DeferredDep::class.java),
        )

        assertEquals(
            "Priority-inversion warnings must not depend on debugLoggingEnabled",
            1,
            inversionWarnings(),
        )
    }

    private fun inversionWarnings(): Int =
        ShadowLog.getLogs().count { it.type == Log.WARN && it.msg.contains(INVERSION_WARNING_MARKER) }

    companion object {
        // Stable substring the production inversion warning must contain.
        private const val INVERSION_WARNING_MARKER = "priority inversion"
    }

    // ── inline fakes (no shared fixtures) ────────────────────────────────────

    private class DeferredDep : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "deferred"
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
    }

    private class NormalDependsOnDeferred : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "normal"
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(DeferredDep::class.java)
    }

    private class SharedDeferred : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "shared"
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
    }

    private class CriticalDependsOnShared : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "critical"
        override fun priority(): StartupPriority = StartupPriority.CRITICAL
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(SharedDeferred::class.java)
    }

    private class NormalDependsOnShared : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "normal"
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(SharedDeferred::class.java)
    }

    private class LonelyDeferred : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "lonely"
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
    }

    private class PlainNormal : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "plain"
    }

    private class SelfDeferred : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "self"
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(SelfDeferred::class.java)
    }

    private class MutualDeferredA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "a"
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(MutualDeferredB::class.java)
    }

    private class MutualDeferredB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "b"
        override fun priority(): StartupPriority = StartupPriority.DEFERRED
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> =
            listOf(MutualDeferredA::class.java)
    }
}

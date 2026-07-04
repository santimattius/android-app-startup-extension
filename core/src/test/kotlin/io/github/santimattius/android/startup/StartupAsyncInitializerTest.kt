package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [StartupAsyncInitializer] interface defaults.
 *
 * Task 5.2 (ADR-3) changes the default [StartupAsyncInitializer.dispatcher] return type to a
 * nullable sentinel (`CoroutineDispatcher? = null`) so the library can distinguish
 * "unoverridden" (defer to `config.defaultAsyncDispatcher`) from an explicit per-instance choice.
 */
class StartupAsyncInitializerTest {

    @Test
    fun `default dispatcher() returns null sentinel`() {
        val initializer = object : StartupAsyncInitializer<String> {
            override suspend fun create(context: Context): String = "x"
        }

        assertNull(
            "Default dispatcher() must return null so the library can apply its own default",
            initializer.dispatcher(),
        )
    }

    @Test
    fun `overridden dispatcher() returns the declared dispatcher`() {
        val initializer = object : StartupAsyncInitializer<String> {
            override fun dispatcher(): CoroutineDispatcher = Dispatchers.IO
            override suspend fun create(context: Context): String = "x"
        }

        assertEquals(Dispatchers.IO, initializer.dispatcher())
    }

    // ── priority() (startup-priority-staggering, ADR-1) ──────────────────────

    @Test
    fun `default priority() returns NORMAL`() {
        val initializer = object : StartupAsyncInitializer<String> {
            override suspend fun create(context: Context): String = "x"
        }

        assertEquals(
            "An initializer with no priority() override must be treated as NORMAL (eager)",
            StartupPriority.NORMAL,
            initializer.priority(),
        )
    }

    @Test
    fun `overridden priority() returns the declared priority`() {
        val initializer = object : StartupAsyncInitializer<String> {
            override fun priority(): StartupPriority = StartupPriority.DEFERRED
            override suspend fun create(context: Context): String = "x"
        }

        assertEquals(StartupPriority.DEFERRED, initializer.priority())
    }

    @Test
    fun `enum ordinal order is CRITICAL then NORMAL then DEFERRED (most-eager = min ordinal)`() {
        assertEquals("CRITICAL must be the most-eager (ordinal 0)", 0, StartupPriority.CRITICAL.ordinal)
        assertEquals("NORMAL must sit between CRITICAL and DEFERRED (ordinal 1)", 1, StartupPriority.NORMAL.ordinal)
        assertEquals("DEFERRED must be the least-eager (ordinal 2)", 2, StartupPriority.DEFERRED.ordinal)
        assertTrue(
            "min-ordinal comparison must rank CRITICAL as more eager than NORMAL and DEFERRED",
            StartupPriority.CRITICAL.ordinal < StartupPriority.NORMAL.ordinal &&
                StartupPriority.NORMAL.ordinal < StartupPriority.DEFERRED.ordinal,
        )
    }
}

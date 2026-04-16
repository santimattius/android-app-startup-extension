package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 4.1 — Mixed sync/async dependencies.
 *
 * Verifies that an [StartupAsyncInitializer] which declares [StartupAsyncInitializer.syncDependencies]
 * receives those sync initializers fully initialized before its own [StartupAsyncInitializer.create]
 * is invoked, and that shared sync dependencies are only initialized once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MixedDependenciesTest {

    private val context: Context = mockk(relaxed = true)
    private val initializer = AppStartupInitializer(context)

    @Before
    fun resetSharedState() {
        syncACreateCount = 0
        syncAWasInitializedBeforeAsync = false
    }

    // ── Spec-Test 1 ───────────────────────────────────────────────────────────

    @Test
    fun `async initializer receives result of sync dependency`() = runTest {
        val result = initializer.doInitialize<String>(AsyncBInitializer::class.java)

        assertEquals("derived-from-sync-value", result)
        assertTrue(
            "SyncA.create() must have been called before AsyncB.create()",
            syncAWasInitializedBeforeAsync
        )
    }

    // ── Spec-Test 2 ───────────────────────────────────────────────────────────

    @Test
    fun `sync dependency is only initialized once even if declared by multiple async initializers`() =
        runTest {
            awaitAll(
                async { initializer.doInitialize<String>(AsyncBInitializer::class.java) },
                async { initializer.doInitialize<String>(AsyncCInitializer::class.java) }
            )

            assertEquals(
                "SyncA.create() must be called exactly once regardless of how many " +
                    "async initializers declare it as a sync dependency",
                1,
                syncACreateCount
            )
        }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Shared mutable state visible to all nested fixture classes via the companion object. */
    companion object {
        @Volatile
        var syncACreateCount = 0

        @Volatile
        var syncAWasInitializedBeforeAsync = false
    }

    private class SyncAInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String {
            syncACreateCount++
            return "sync-value"
        }
    }

    /** Async initializer that depends on [SyncAInitializer] via [syncDependencies]. */
    private class AsyncBInitializer : StartupAsyncInitializer<String> {
        override fun syncDependencies(): List<Class<out StartupSyncInitializer<*>>> =
            listOf(SyncAInitializer::class.java)

        override suspend fun create(context: Context): String {
            // SyncA must already be initialized at this point
            check(syncACreateCount > 0) {
                "SyncA was not initialized before AsyncB.create() — syncDependencies not honoured"
            }
            syncAWasInitializedBeforeAsync = true
            return "derived-from-sync-value"
        }
    }

    /** A second async initializer that also declares [SyncAInitializer] as a sync dependency. */
    private class AsyncCInitializer : StartupAsyncInitializer<String> {
        override fun syncDependencies(): List<Class<out StartupSyncInitializer<*>>> =
            listOf(SyncAInitializer::class.java)

        override suspend fun create(context: Context): String = "c-result"
    }
}

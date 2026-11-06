package io.github.santimattius.android.startup

import android.content.Context
import android.os.Looper
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Spec 4.1 — Mixed sync/async dependencies.
 *
 * Verifies that an [StartupAsyncInitializer] which declares [StartupAsyncInitializer.syncDependencies]
 * receives those sync initializers fully initialized before its own [StartupAsyncInitializer.create]
 * is invoked, and that shared sync dependencies are only initialized once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MixedDependenciesTest {

    private val context: Context = mockk(relaxed = true)
    private val initializer = AppStartupInitializer(context)

    @Before
    fun resetSharedState() {
        syncACreateCount = 0
        syncAWasInitializedBeforeAsync = false
        syncDependencyCreateThread = null
        asyncCreateThread = null
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

    @Test
    fun `sync dependency resolved from background async initialization creates on Android main`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "async-initializer-caller")
        }

        try {
            val result = awaitWithMainLooper(
                executor.submit<String> {
                    runBlocking {
                        initializer.doInitialize<String>(BackgroundAsyncInitializer::class.java)
                    }
                },
            )

            assertEquals("async-created", result)
            assertSame(Looper.getMainLooper().thread, syncDependencyCreateThread)
            assertTrue(asyncCreateThread !== Looper.getMainLooper().thread)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun <T> awaitWithMainLooper(future: Future<T>): T {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!future.isDone && System.nanoTime() < deadlineNs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Shared mutable state visible to all nested fixture classes via the companion object. */
    companion object {
        @Volatile
        var syncACreateCount = 0

        @Volatile
        var syncAWasInitializedBeforeAsync = false

        @Volatile
        var syncDependencyCreateThread: Thread? = null

        @Volatile
        var asyncCreateThread: Thread? = null
    }

    private class SyncAInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String {
            syncACreateCount++
            return "sync-value"
        }
    }

    private class ThreadAffineSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String {
            syncDependencyCreateThread = Thread.currentThread()
            return "sync-created"
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

    private class BackgroundAsyncInitializer : StartupAsyncInitializer<String> {
        override fun syncDependencies(): List<Class<out StartupSyncInitializer<*>>> =
            listOf(ThreadAffineSyncInitializer::class.java)

        override fun dispatcher() = Dispatchers.Default

        override suspend fun create(context: Context): String {
            asyncCreateThread = Thread.currentThread()
            return "async-created"
        }
    }

    /** A second async initializer that also declares [SyncAInitializer] as a sync dependency. */
    private class AsyncCInitializer : StartupAsyncInitializer<String> {
        override fun syncDependencies(): List<Class<out StartupSyncInitializer<*>>> =
            listOf(SyncAInitializer::class.java)

        override suspend fun create(context: Context): String = "c-result"
    }
}

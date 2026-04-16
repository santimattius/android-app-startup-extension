package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * Spec 4.3 — Dispatcher configurable por initializer.
 *
 * Verifies that [StartupAsyncInitializer.create] runs on the [CoroutineDispatcher]
 * declared by the initializer, not on the engine's default dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DispatcherPerInitializerTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var initializer: AppStartupInitializer

    @Before
    fun setUp() {
        capturedThread = null
        initializer = AppStartupInitializer(context)
    }

    @After
    fun tearDown() {
        customDispatcher.close()
    }

    // ── Spec-Test: create() runs on the declared dispatcher ───────────────────

    @Test
    fun `create() runs on the dispatcher declared by the initializer`() = runTest {
        initializer.doInitialize<String>(CustomDispatcherInitializer::class.java)

        assertNotNull("capturedThread must be set inside create()", capturedThread)
        assertTrue(
            "create() must run on the custom dispatcher thread, got: $capturedThread",
            capturedThread!!.contains(CUSTOM_THREAD_NAME)
        )
    }

    // ── Spec-Test: default dispatcher is Dispatchers.Default ─────────────────

    @Test
    fun `create() runs on Dispatchers Default when no dispatcher is declared`() = runTest {
        initializer.doInitialize<String>(DefaultDispatcherInitializer::class.java)

        assertNotNull("capturedThread must be set inside create()", capturedThread)
        assertFalse(
            "Default initializer must not run on the custom dispatcher thread, got: $capturedThread",
            capturedThread!!.contains(CUSTOM_THREAD_NAME)
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    companion object {
        private const val CUSTOM_THREAD_NAME = "test-custom-dispatcher"

        @Volatile
        var capturedThread: String? = null

        /**
         * A single-thread dispatcher backed by a named thread, giving us a deterministic
         * thread name to assert on — independent of JVM internals (both [Dispatchers.Default]
         * and [Dispatchers.IO] share the same thread pool on the JVM and would be ambiguous).
         */
        val customDispatcher: ExecutorCoroutineDispatcher = Executors
            .newSingleThreadExecutor { r -> Thread(r, CUSTOM_THREAD_NAME).also { it.isDaemon = true } }
            .asCoroutineDispatcher()
    }

    /** Initializer that pins [create] to [customDispatcher]. */
    private class CustomDispatcherInitializer : StartupAsyncInitializer<String> {
        override fun dispatcher(): CoroutineDispatcher = customDispatcher

        override suspend fun create(context: Context): String {
            capturedThread = Thread.currentThread().name
            return "result"
        }
    }

    /** Initializer that relies on the default dispatcher (no override). */
    private class DefaultDispatcherInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            capturedThread = Thread.currentThread().name
            return "result"
        }
    }
}

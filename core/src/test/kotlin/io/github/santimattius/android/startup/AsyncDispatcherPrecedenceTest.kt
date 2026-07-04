package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * Spec: startup-dispatcher-config (ADR-3) — effective dispatcher precedence.
 *
 * Effective resolution precedence for the dispatcher `create()` runs on:
 * per-instance `dispatcher()` (non-null) > `config.defaultAsyncDispatcher` > `Dispatchers.Default`.
 *
 * Thread names are asserted via a single-thread named dispatcher, giving a deterministic
 * signal for which dispatcher actually executed `create()` (both `Dispatchers.Default` and
 * `Dispatchers.IO` share the JVM pool and would be ambiguous).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AsyncDispatcherPrecedenceTest {

    private val context: Context = mockk(relaxed = true)

    @After
    fun tearDown() {
        capturedThread = null
        AppStartupInitializer.configure { }
    }

    @Test
    fun `unoverridden initializer honors config defaultAsyncDispatcher`() = runTest {
        AppStartupInitializer.configure { defaultAsyncDispatcher = configDispatcher }
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(NoOverrideInitializer::class.java)

        assertNotNull("capturedThread must be set inside create()", capturedThread)
        assertTrue(
            "Unoverridden create() must run on config.defaultAsyncDispatcher thread, got: $capturedThread",
            capturedThread!!.contains(CONFIG_THREAD_NAME),
        )
    }

    @Test
    fun `per-instance dispatcher override wins over config defaultAsyncDispatcher`() = runTest {
        AppStartupInitializer.configure { defaultAsyncDispatcher = configDispatcher }
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(OverrideInitializer::class.java)

        assertNotNull("capturedThread must be set inside create()", capturedThread)
        assertTrue(
            "Per-instance dispatcher() must win over config default, got: $capturedThread",
            capturedThread!!.contains(OVERRIDE_THREAD_NAME),
        )
    }

    @Test
    fun `both unset falls back to Dispatchers Default`() = runTest {
        val initializer = AppStartupInitializer(context)

        initializer.doInitialize<String>(NoOverrideInitializer::class.java)

        assertNotNull("capturedThread must be set inside create()", capturedThread)
        assertTrue(
            "With no config and no override, create() must run on Dispatchers.Default, got: $capturedThread",
            capturedThread!!.contains(DEFAULT_DISPATCHER_THREAD_MARKER),
        )
    }

    companion object {
        private const val CONFIG_THREAD_NAME = "test-config-dispatcher"
        private const val OVERRIDE_THREAD_NAME = "test-override-dispatcher"
        private const val DEFAULT_DISPATCHER_THREAD_MARKER = "DefaultDispatcher"

        @Volatile
        var capturedThread: String? = null

        val configDispatcher: ExecutorCoroutineDispatcher = Executors
            .newSingleThreadExecutor { r -> Thread(r, CONFIG_THREAD_NAME).also { it.isDaemon = true } }
            .asCoroutineDispatcher()

        val overrideDispatcher: ExecutorCoroutineDispatcher = Executors
            .newSingleThreadExecutor { r -> Thread(r, OVERRIDE_THREAD_NAME).also { it.isDaemon = true } }
            .asCoroutineDispatcher()
    }

    /** No dispatcher override — should defer to config default, else Dispatchers.Default. */
    private class NoOverrideInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            capturedThread = Thread.currentThread().name
            return "result"
        }
    }

    /** Pins create() to [overrideDispatcher], which must beat any config default. */
    private class OverrideInitializer : StartupAsyncInitializer<String> {
        override fun dispatcher(): CoroutineDispatcher = overrideDispatcher

        override suspend fun create(context: Context): String {
            capturedThread = Thread.currentThread().name
            return "result"
        }
    }
}

package io.github.santimattius.android.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupConfigTest {

    /**
     * Inline fake [FirstFrameSignal] backed by a [CompletableDeferred] so tests can drive the
     * deferred-startup gate deterministically (no real Android frame). [fire] resolves the signal.
     */
    private class FakeFirstFrameSignal : FirstFrameSignal {
        private val deferred = CompletableDeferred<Unit>()
        override suspend fun await() = deferred.await()
        fun fire() {
            deferred.complete(Unit)
        }
    }

    @After
    fun resetConfig() {
        AppStartupInitializer.configure { }
    }

    @Test
    fun `default config has debug logging disabled`() {
        assertFalse(StartupExtensionLogger.isDebugEnabled)
    }

    @Test
    fun `configure enables debug logging`() {
        AppStartupInitializer.configure { debugLoggingEnabled = true }

        assertTrue(StartupExtensionLogger.isDebugEnabled)
    }

    @Test
    fun `configure disables debug logging after being enabled`() {
        AppStartupInitializer.configure { debugLoggingEnabled = true }
        AppStartupInitializer.configure { debugLoggingEnabled = false }

        assertFalse(StartupExtensionLogger.isDebugEnabled)
    }

    @Test
    fun `default ordering strategy is Lazy`() {
        val config = AppStartupConfig.Builder().build()

        assertTrue(config.syncOrderingStrategy is SyncOrderingStrategy.Lazy)
    }

    @Test
    fun `default strict mode check is disabled`() {
        val config = AppStartupConfig.Builder().build()

        assertFalse(config.strictModeCheckEnabled)
    }

    @Test
    fun `default async initializer strategy is Concurrent`() {
        val config = AppStartupConfig.Builder().build()

        assertTrue(config.asyncInitializerStrategy is AsyncInitializerStrategy.Concurrent)
    }

    @Test
    fun `Builder reflects all configured values`() {
        val config = AppStartupConfig.Builder().apply {
            debugLoggingEnabled = true
            strictModeCheckEnabled = true
            syncOrderingStrategy = SyncOrderingStrategy.Topological
            asyncInitializerStrategy = AsyncInitializerStrategy.Validated
        }.build()

        assertTrue(config.debugLoggingEnabled)
        assertTrue(config.strictModeCheckEnabled)
        assertTrue(config.syncOrderingStrategy is SyncOrderingStrategy.Topological)
        assertTrue(config.asyncInitializerStrategy is AsyncInitializerStrategy.Validated)
    }

    // ── maxConcurrentAsyncInitializers ──────────────────────────────────────

    @Test
    fun `default maxConcurrentAsyncInitializers is null (unbounded)`() {
        val config = AppStartupConfig.Builder().build()

        assertNull(config.maxConcurrentAsyncInitializers)
    }

    @Test
    fun `maxConcurrentAsyncInitializers of zero normalizes to null`() {
        val config = AppStartupConfig.Builder().apply {
            maxConcurrentAsyncInitializers = 0
        }.build()

        assertNull(config.maxConcurrentAsyncInitializers)
    }

    @Test
    fun `maxConcurrentAsyncInitializers negative value normalizes to null`() {
        val config = AppStartupConfig.Builder().apply {
            maxConcurrentAsyncInitializers = -5
        }.build()

        assertNull(config.maxConcurrentAsyncInitializers)
    }

    @Test
    fun `maxConcurrentAsyncInitializers positive value is preserved`() {
        val config = AppStartupConfig.Builder().apply {
            maxConcurrentAsyncInitializers = 3
        }.build()

        assertEquals(3, config.maxConcurrentAsyncInitializers)
    }

    // ── defaultAsyncDispatcher ───────────────────────────────────────────────

    @Test
    fun `default defaultAsyncDispatcher is Dispatchers Default`() {
        val config = AppStartupConfig.Builder().build()

        assertEquals(Dispatchers.Default, config.defaultAsyncDispatcher)
    }

    @Test
    fun `Builder reflects configured defaultAsyncDispatcher`() {
        val config = AppStartupConfig.Builder().apply {
            defaultAsyncDispatcher = Dispatchers.IO
        }.build()

        assertEquals(Dispatchers.IO, config.defaultAsyncDispatcher)
    }

    // ── strictModeConcurrencyThreshold ───────────────────────────────────────

    @Test
    fun `default strictModeConcurrencyThreshold is available processors`() {
        val config = AppStartupConfig.Builder().build()

        assertEquals(Runtime.getRuntime().availableProcessors(), config.strictModeConcurrencyThreshold)
    }

    @Test
    fun `Builder reflects configured strictModeConcurrencyThreshold`() {
        val config = AppStartupConfig.Builder().apply {
            strictModeConcurrencyThreshold = 8
        }.build()

        assertEquals(8, config.strictModeConcurrencyThreshold)
    }

    // ── firstFrameSignal (startup-priority-staggering, ADR-2) ────────────────

    @Test
    fun `default firstFrameSignal is null`() {
        val config = AppStartupConfig.Builder().build()

        assertNull(
            "firstFrameSignal must default to null so the scheduler resolves the Android default lazily",
            config.firstFrameSignal,
        )
    }

    @Test
    fun `Builder reflects configured firstFrameSignal and it survives build unchanged`() {
        val fake = FakeFirstFrameSignal()

        val config = AppStartupConfig.Builder().apply {
            firstFrameSignal = fake
        }.build()

        assertSame(
            "An injected firstFrameSignal must be preserved verbatim through build()",
            fake,
            config.firstFrameSignal,
        )
    }

    // ── deferredStartupTimeoutMs (startup-priority-staggering, ADR-5) ────────

    @Test
    fun `default deferredStartupTimeoutMs is 5000`() {
        val config = AppStartupConfig.Builder().build()

        assertEquals(
            "Headless/no-UI processes must eventually flush DEFERRED work via a 5s default timeout",
            5_000L,
            config.deferredStartupTimeoutMs,
        )
    }

    @Test
    fun `Builder reflects configured deferredStartupTimeoutMs`() {
        val config = AppStartupConfig.Builder().apply {
            deferredStartupTimeoutMs = 1_234L
        }.build()

        assertEquals(1_234L, config.deferredStartupTimeoutMs)
    }
}

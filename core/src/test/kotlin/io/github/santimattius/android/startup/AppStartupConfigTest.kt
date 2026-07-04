package io.github.santimattius.android.startup

import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupConfigTest {

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
}

package io.github.santimattius.android.startup

import org.junit.After
import org.junit.Assert.assertFalse
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
}

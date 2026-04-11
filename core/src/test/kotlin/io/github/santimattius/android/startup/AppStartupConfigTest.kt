package io.github.santimattius.android.startup

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupLoggingTest {

    @After
    fun resetLogging() {
        AppStartupInitializer.enableDebugLogging(false)
    }

    @Test
    fun `debug logging is disabled by default`() {
        assertFalse(StartupExtensionLogger.isDebugEnabled)
    }

    @Test
    fun `debug logging can be enabled at runtime`() {
        AppStartupInitializer.enableDebugLogging(true)

        assertTrue(StartupExtensionLogger.isDebugEnabled)
    }

    @Test
    fun `debug logging can be disabled after being enabled`() {
        AppStartupInitializer.enableDebugLogging(true)
        AppStartupInitializer.enableDebugLogging(false)

        assertFalse(StartupExtensionLogger.isDebugEnabled)
    }
}

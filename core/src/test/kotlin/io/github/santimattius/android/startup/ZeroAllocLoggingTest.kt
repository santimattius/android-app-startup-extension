package io.github.santimattius.android.startup

import android.content.Context
import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZeroAllocLoggingTest {

    @After
    fun reset() {
        AppStartupInitializer.configure { }
        ShadowLog.clear()
    }

    @Test
    fun `message lambdas are never invoked when debug logging is disabled`() {
        StartupExtensionLogger.enable(false)
        var invocations = 0

        StartupExtensionLogger.info { invocations++; "info" }
        StartupExtensionLogger.warning { invocations++; "warning" }
        StartupExtensionLogger.error({ invocations++; "error" }, RuntimeException("x"))

        assertEquals(0, invocations)
        assertTrue(ShadowLog.getLogsForTag("StartupLogger").isEmpty())
    }

    @Test
    fun `message lambdas are invoked exactly once and logged when debug logging is enabled`() {
        StartupExtensionLogger.enable(true)
        var invocations = 0

        StartupExtensionLogger.info { invocations++; "hello" }
        StartupExtensionLogger.warning { invocations++; "careful" }
        StartupExtensionLogger.error({ invocations++; "broken" }, RuntimeException("x"))

        assertEquals(3, invocations)
        assertEquals(
            listOf(Log.INFO to "hello", Log.WARN to "careful", Log.ERROR to "broken"),
            ShadowLog.getLogsForTag("StartupLogger").map { it.type to it.msg },
        )
    }

    @Test
    fun `startup logs through the lazy logger only when debug logging is enabled`() {
        val context = mockk<Context>(relaxed = true)

        AppStartupInitializer.configure { debugLoggingEnabled = false }
        AppStartupInitializer(context).doInitialize<String>(Sync::class.java)
        assertTrue(ShadowLog.getLogsForTag("StartupLogger").isEmpty())

        AppStartupInitializer.configure { debugLoggingEnabled = true }
        AppStartupInitializer(context).doInitialize<String>(Sync::class.java)
        val messages = ShadowLog.getLogsForTag("StartupLogger").map { it.msg }
        assertTrue(messages.any { it.startsWith("Initializing") })
        assertFalse(messages.isEmpty())
    }

    private class Sync : StartupSyncInitializer<String> {
        override fun create(context: Context) = "ok"
    }
}

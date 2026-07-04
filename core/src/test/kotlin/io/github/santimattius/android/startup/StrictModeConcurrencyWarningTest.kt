package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec: startup-strictmode-warnings (tasks 7.1/7.2, ADR-4).
 *
 * When `strictModeCheckEnabled` is on and the observed `concurrentActiveCount` snapshot exceeds
 * `strictModeConcurrencyThreshold`, the library emits AT MOST ONE warning per startup. Emission is
 * silent when the flag is off or concurrency stays within the threshold.
 *
 * The warning goes through [StartupExtensionLogger] (the library's single logging seam), so
 * `debugLoggingEnabled` is turned on here to route it to `Log.w`, which Robolectric captures via
 * [ShadowLog]. This mirrors real debug builds, where strict mode and debug logging are enabled
 * together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class StrictModeConcurrencyWarningTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        ShadowLog.clear()
        allEntered = CountDownLatch(3)
        gate = CountDownLatch(1)
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        gate.countDown()
        AppStartupInitializer.configure { }
    }

    @Test
    fun `warns once when strict mode on and concurrency exceeds threshold`() {
        AppStartupInitializer.configure {
            strictModeCheckEnabled = true
            debugLoggingEnabled = true
            strictModeConcurrencyThreshold = 1
            defaultAsyncDispatcher = Dispatchers.IO
        }
        val initializer = AppStartupInitializer(context, Dispatchers.IO)
        stubManifest(Warn1::class.java, Warn2::class.java, Warn3::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)

        // Once all three have entered create(), every concurrency snapshot has been taken.
        allEntered.await(5, TimeUnit.SECONDS)
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }

        assertEquals(
            "Exactly one strict-mode concurrency warning must be emitted per startup",
            1,
            strictModeWarnings(),
        )
    }

    @Test
    fun `silent when strict mode flag is off even if concurrency exceeds threshold`() {
        AppStartupInitializer.configure {
            strictModeCheckEnabled = false
            debugLoggingEnabled = true
            strictModeConcurrencyThreshold = 1
            defaultAsyncDispatcher = Dispatchers.IO
        }
        val initializer = AppStartupInitializer(context, Dispatchers.IO)
        stubManifest(Warn1::class.java, Warn2::class.java, Warn3::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        allEntered.await(5, TimeUnit.SECONDS)
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }

        assertEquals("No warning when strictModeCheckEnabled is false", 0, strictModeWarnings())
    }

    @Test
    fun `silent when concurrency stays within threshold`() {
        AppStartupInitializer.configure {
            strictModeCheckEnabled = true
            debugLoggingEnabled = true
            strictModeConcurrencyThreshold = 10
            defaultAsyncDispatcher = Dispatchers.IO
        }
        val initializer = AppStartupInitializer(context, Dispatchers.IO)
        stubManifest(Warn1::class.java, Warn2::class.java, Warn3::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        allEntered.await(5, TimeUnit.SECONDS)
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }

        assertEquals("No warning when peak concurrency (3) stays under threshold (10)", 0, strictModeWarnings())
    }

    private fun strictModeWarnings(): Int =
        ShadowLog.getLogs().count { logItem ->
            logItem.type == Log.WARN && logItem.msg.contains(STRICT_MODE_WARNING_MARKER)
        }

    private fun stubManifest(vararg classes: Class<*>) {
        val bundle = Bundle().apply {
            classes.forEach { putString(it.name, "async-initializer") }
        }
        val providerInfo = ProviderInfo().apply { metaData = bundle }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns providerInfo
    }

    companion object {
        // Stable substring the production warning message must contain.
        private const val STRICT_MODE_WARNING_MARKER = "Async startup concurrency"

        @Volatile
        var allEntered = CountDownLatch(3)

        @Volatile
        var gate = CountDownLatch(1)
    }

    private open class WarnBarrier : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            allEntered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            return "ok"
        }
    }

    private class Warn1 : WarnBarrier()
    private class Warn2 : WarnBarrier()
    private class Warn3 : WarnBarrier()
}

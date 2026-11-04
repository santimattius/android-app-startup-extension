package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An app that includes the library but declares no initializers has a provider without
 * `<meta-data>`: `ProviderInfo.metaData` is null. Startup must treat that exactly like an empty
 * Bundle (nothing to initialize) instead of crashing the process at launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class NoDeclaredInitializersTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        AppStartupInitializer.configure { }
    }

    @Test
    fun `null provider metadata initializes nothing and does not throw`() {
        assertNothingToInitialize(metaData = null)
    }

    @Test
    fun `empty provider metadata initializes nothing and does not throw`() {
        assertNothingToInitialize(metaData = Bundle())
    }

    private fun assertNothingToInitialize(metaData: Bundle?) {
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { this.metaData = metaData }
        val initializer = AppStartupInitializer(context, Dispatchers.Unconfined)

        initializer.discoverAndInitialize(InitializationProvider::class.java)

        runBlocking { initializer.awaitAllStartJobs() }
        assertTrue("No start jobs should be pending", initializer.isAllStartedJobsDone())
        assertTrue(
            "No metrics expected when nothing is declared",
            initializer.metricsFlow.replayCache.isEmpty(),
        )
    }
}

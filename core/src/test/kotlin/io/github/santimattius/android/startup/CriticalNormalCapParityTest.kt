package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec: startup-priority-staggering — CRITICAL == NORMAL in v1 under concurrency cap.
 *
 * Verifies that CRITICAL roots do not bypass the global semaphore and compete on equal footing
 * with NORMAL roots: peak concurrency respects the cap, both complete eagerly, and nothing is
 * routed to the deferred path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class CriticalNormalCapParityTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        created.clear()
        active.set(0)
        peak.set(0)
        capReached = CountDownLatch(1)
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
    fun `CRITICAL and NORMAL roots share the semaphore equally at cap 1 without deferral`() {
        AppStartupInitializer.configure {
            maxConcurrentAsyncInitializers = 1
            defaultAsyncDispatcher = Dispatchers.IO
        }
        val initializer = AppStartupInitializer(context, Dispatchers.IO)
        stubManifest(CapCriticalInit::class.java, CapNormalInit::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)

        assertTrue(
            "At least one initializer must reach create() under cap=1",
            capReached.await(5, TimeUnit.SECONDS),
        )
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }

        assertTrue("CRITICAL must complete eagerly", created.contains("CapCriticalInit"))
        assertTrue("NORMAL must complete eagerly", created.contains("CapNormalInit"))
        assertTrue(
            "Peak concurrency must respect cap=1 — CRITICAL does not bypass the semaphore",
            peak.get() <= 1,
        )
        assertTrue(
            "Neither CRITICAL nor NORMAL may be routed to the deferred path",
            initializer.coroutinesEngine.deferredStartJobs.isEmpty(),
        )
        val metrics = initializer.metricsFlow.replayCache
        assertTrue(
            "Every metric's concurrentActiveCount must stay within cap=1",
            metrics.all { it.concurrentActiveCount <= 1 },
        )
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
        val created: MutableList<String> = mutableListOf()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        @Volatile
        var capReached = CountDownLatch(1)

        @Volatile
        var gate = CountDownLatch(1)
    }

    private abstract class CapBarrier : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            val now = active.incrementAndGet()
            peak.updateAndGet { current -> maxOf(current, now) }
            if (now == 1) {
                capReached.countDown()
            }
            gate.await(5, TimeUnit.SECONDS)
            active.decrementAndGet()
            val name = javaClass.simpleName
            created += name
            return name
        }
    }

    private class CapCriticalInit : CapBarrier() {
        override fun priority(): StartupPriority = StartupPriority.CRITICAL
    }

    private class CapNormalInit : CapBarrier()
}

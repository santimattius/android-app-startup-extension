package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncInitializerThreadAffinityTest {
    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun resetState() {
        callerThread = null
        createThread = null
        backgroundEntered = CountDownLatch(1)
        releaseBackground = CountDownLatch(1)
        unrelatedComponentCreated = CountDownLatch(1)
        cycleDependenciesEntered = CountDownLatch(2)
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
    }

    @Test
    fun `public sync initialization invoked from a background thread creates on the caller thread`() {
        val initializer = AppStartupInitializer(context)
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sync-initializer-caller")
            }

        try {
            val result =
                awaitWithMainLooper(
                    executor.submit<String> {
                        callerThread = Thread.currentThread()
                        initializer.initializeComponent(ThreadAffineSyncInitializer::class.java)
                    },
                )

            assertEquals("created", result)
            assertSame(callerThread, createThread)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `provider-driven sync discovery creates inline on Android main thread`() {
        val initializer = AppStartupInitializer(context)
        stubSyncManifest(ThreadAffineSyncInitializer::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)

        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        assertSame(Thread.currentThread(), createThread)
    }

    @Test
    fun `public sync initialization from a background thread propagates create failure`() {
        val initializer = AppStartupInitializer(context)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val failure =
                assertThrows(ExecutionException::class.java) {
                    awaitWithMainLooper(
                        executor.submit<String> {
                            initializer.initializeComponent(FailingSyncInitializer::class.java)
                        },
                    )
                }

            assertSame(IllegalStateException::class.java, failure.cause?.cause?.javaClass)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `background handoff permits nested sync initialization without deadlock`() {
        val initializer = AppStartupInitializer(context)
        nestedInitializer = initializer
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result =
                awaitWithMainLooper(
                    executor.submit<String> {
                        initializer.initializeComponent(NestedOuterInitializer::class.java)
                    },
                )

            assertEquals("outer(inner)", result)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `unrelated sync initializer completes while another component is blocked`() {
        val initializer = AppStartupInitializer(context)
        val executorA = Executors.newSingleThreadExecutor()
        val executorB = Executors.newSingleThreadExecutor()
        var background: Future<String>? = null
        var unrelated: Future<String>? = null
        var unrelatedCompleted = false

        try {
            background =
                executorA.submit<String> {
                    initializer.initializeComponent(BackgroundWaitingInitializer::class.java)
                }
            assertTrue(backgroundEntered.await(5, TimeUnit.SECONDS))
            unrelated =
                executorB.submit<String> {
                    initializer.initializeComponent(UnrelatedInitializer::class.java)
                }
            unrelatedCompleted = unrelatedComponentCreated.await(5, TimeUnit.SECONDS)
        } finally {
            releaseBackground.countDown()
            runCatching { background?.get(5, TimeUnit.SECONDS) }
            runCatching { unrelated?.get(5, TimeUnit.SECONDS) }
            executorA.shutdownNow()
            executorB.shutdownNow()
            executorA.awaitTermination(5, TimeUnit.SECONDS)
            executorB.awaitTermination(5, TimeUnit.SECONDS)
        }

        assertTrue("The unrelated initializer was blocked by another component", unrelatedCompleted)
    }

    @Test
    fun `concurrent cyclic sync roots fail without hanging`() {
        val initializer = AppStartupInitializer(context)
        val executor =
            Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "sync-cycle").apply { isDaemon = true }
            }
        var first: Result<String>? = null
        var second: Result<String>? = null

        try {
            val a = executor.submit<String> { initializer.initializeComponent(CyclicAInitializer::class.java) }
            val b = executor.submit<String> { initializer.initializeComponent(CyclicBInitializer::class.java) }
            first = runCatching { a.get(5, TimeUnit.SECONDS) }
            second = runCatching { b.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        assertFalse(first?.exceptionOrNull() is TimeoutException)
        assertFalse(second?.exceptionOrNull() is TimeoutException)
        assertTrue(first?.isFailure == true)
        assertTrue(second?.isFailure == true)
        assertTrue(
            first?.hasCauseMessage("Cross-thread cycle detected") == true ||
                second?.hasCauseMessage("Cross-thread cycle detected") == true,
        )
    }

    private fun Result<*>.hasCauseMessage(expected: String): Boolean {
        var throwable = exceptionOrNull()
        while (throwable != null) {
            if (throwable.message?.contains(expected) == true) return true
            throwable = throwable.cause
        }
        return false
    }

    private fun stubSyncManifest(vararg classes: Class<*>) {
        val bundle =
            Bundle().apply {
                classes.forEach { putString(it.name, "sync-initializer") }
            }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
    }

    private fun <T> awaitWithMainLooper(future: Future<T>): T {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!future.isDone && System.nanoTime() < deadlineNs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    private class FailingSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String = error("create failed")
    }

    class ThreadAffineSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String {
            createThread = Thread.currentThread()
            return "created"
        }
    }

    private class BackgroundWaitingInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String {
            backgroundEntered.countDown()
            check(releaseBackground.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting for test cleanup"
            }
            return "background"
        }
    }

    private class UnrelatedInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String {
            unrelatedComponentCreated.countDown()
            return "unrelated"
        }
    }

    private class CyclicAInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"

        override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> {
            cycleDependenciesEntered.countDown()
            check(cycleDependenciesEntered.await(5, TimeUnit.SECONDS))
            return listOf(CyclicBInitializer::class.java)
        }
    }

    private class CyclicBInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "b"

        override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> {
            cycleDependenciesEntered.countDown()
            check(cycleDependenciesEntered.await(5, TimeUnit.SECONDS))
            return listOf(CyclicAInitializer::class.java)
        }
    }

    private class NestedInnerInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "inner"
    }

    private class NestedOuterInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String =
            "outer(${nestedInitializer.initializeComponent<String>(NestedInnerInitializer::class.java)})"
    }

    private companion object {
        @Volatile
        var callerThread: Thread? = null

        @Volatile
        var createThread: Thread? = null

        lateinit var backgroundEntered: CountDownLatch
        lateinit var releaseBackground: CountDownLatch
        lateinit var unrelatedComponentCreated: CountDownLatch
        lateinit var cycleDependenciesEntered: CountDownLatch
        lateinit var nestedInitializer: AppStartupInitializer
    }
}

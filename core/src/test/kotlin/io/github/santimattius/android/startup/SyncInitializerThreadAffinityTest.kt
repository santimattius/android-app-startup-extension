package io.github.santimattius.android.startup

import android.content.Context
import android.os.Build
import android.os.Looper
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncInitializerThreadAffinityTest {
    private val context: Context = mockk(relaxed = true)

    @Before
    fun resetState() {
        createThread = null
    }

    @Test
    fun `public sync initialization invoked from a background thread creates on Android main`() {
        val initializer = AppStartupInitializer(context)
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sync-initializer-caller")
            }

        try {
            val result =
                awaitWithMainLooper(
                    executor.submit<String> {
                        initializer.initializeComponent(MainThreadSyncInitializer::class.java)
                    },
                )

            assertEquals("created", result)
            assertSame(Looper.getMainLooper().thread, createThread)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `public sync initialization from a background thread propagates create failure`() {
        val initializer = AppStartupInitializer(context)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val failure = assertThrows(ExecutionException::class.java) {
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

    private class MainThreadSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context): String {
            createThread = Thread.currentThread()
            return "created"
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
        var createThread: Thread? = null

        lateinit var nestedInitializer: AppStartupInitializer
    }
}

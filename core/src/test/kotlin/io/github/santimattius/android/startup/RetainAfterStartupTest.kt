package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Bundle
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainAfterStartupTest {
    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private lateinit var initializer: AppStartupInitializer

    @Before
    fun setup() {
        initializer = AppStartupInitializer(context)
        RetainedSyncInitializer.createCount = 0
        TransientSyncInitializer.createCount = 0
        RetainedAsyncInitializer.createCount = 0
        TransientAsyncInitializer.createCount = 0
        TransientSharedDependency.createCount.set(0)
        GatedStartupInitializer.started = CompletableDeferred()
        GatedStartupInitializer.release = CompletableDeferred()
        ConcurrentTransientInitializer.createCount.set(0)
        ConcurrentTransientInitializer.release = CompletableDeferred()
    }

    @After
    fun tearDown() {
        AppStartupInitializer.configure { }
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    @Test
    fun `retained sync initializer runs create() once across multiple calls`() {
        initializer.doInitialize<String>(RetainedSyncInitializer::class.java)
        initializer.doInitialize<String>(RetainedSyncInitializer::class.java)

        assertEquals(1, RetainedSyncInitializer.createCount)
    }

    @Test
    fun `transient sync initializer re-runs create() on every call`() {
        initializer.doInitialize<String>(TransientSyncInitializer::class.java)
        initializer.doInitialize<String>(TransientSyncInitializer::class.java)

        assertEquals(2, TransientSyncInitializer.createCount)
    }

    // ── Async ─────────────────────────────────────────────────────────────────

    @Test
    fun `retained async initializer runs create() once across multiple calls`() =
        runTest {
            initializer.doInitialize<String>(RetainedAsyncInitializer::class.java)
            initializer.doInitialize<String>(RetainedAsyncInitializer::class.java)

            assertEquals(1, RetainedAsyncInitializer.createCount)
        }

    @Test
    fun `transient async initializer re-runs create() on every call`() =
        runTest {
            initializer.doInitialize<String>(TransientAsyncInitializer::class.java)
            initializer.doInitialize<String>(TransientAsyncInitializer::class.java)

            assertEquals(2, TransientAsyncInitializer.createCount)
        }

    @Test
    fun `transient shared async dependency is deduplicated within one startup graph`() =
        runTest {
            AppStartupInitializer.configure {
                asyncInitializerStrategy = AsyncInitializerStrategy.Concurrent
                defaultAsyncDispatcher = Dispatchers.IO
            }
            every { context.applicationContext } returns context
            every { context.classLoader } returns javaClass.classLoader
            every { context.packageName } returns "io.github.santimattius.test"
            every { context.packageManager } returns packageManager
            val startupInitializer = AppStartupInitializer(context, Dispatchers.IO)
            stubAsyncManifest(TransientDependencyParentOne::class.java, TransientDependencyParentTwo::class.java)

            startupInitializer.discoverAndInitialize(InitializationProvider::class.java)
            startupInitializer.awaitAllStartJobs()

            assertEquals(
                "A transient dependency must be shared by all nodes in its startup graph",
                1,
                TransientSharedDependency.createCount.get(),
            )

            startupInitializer.doInitialize<String>(TransientSharedDependency::class.java)

            assertEquals(
                "The transient dependency must be released after its startup graph completes",
                2,
                TransientSharedDependency.createCount.get(),
            )
        }

    @Test
    fun `on-demand transient async initializer is not cached while startup graph remains active`() =
        runBlocking {
            AppStartupInitializer.configure {
                asyncInitializerStrategy = AsyncInitializerStrategy.Concurrent
                defaultAsyncDispatcher = Dispatchers.IO
            }
            every { context.applicationContext } returns context
            every { context.classLoader } returns javaClass.classLoader
            every { context.packageName } returns "io.github.santimattius.test"
            every { context.packageManager } returns packageManager
            val startupInitializer = AppStartupInitializer(context, Dispatchers.IO)
            stubAsyncManifest(GatedStartupInitializer::class.java)

            try {
                startupInitializer.discoverAndInitialize(InitializationProvider::class.java)
                withTimeout(5_000) { GatedStartupInitializer.started.await() }

                startupInitializer.doInitialize<String>(TransientAsyncInitializer::class.java)
                startupInitializer.doInitialize<String>(TransientAsyncInitializer::class.java)

                assertEquals(
                    "Public on-demand calls must not share startup-graph transient retention",
                    2,
                    TransientAsyncInitializer.createCount,
                )
            } finally {
                GatedStartupInitializer.release.complete(Unit)
                boundedCleanup { startupInitializer.awaitAllStartJobs() }
            }
        }

    @Test
    fun `on-demand call does not join startup-owned transient in-flight work`() =
        runTest {
            AppStartupInitializer.configure {
                asyncInitializerStrategy = AsyncInitializerStrategy.Concurrent
                defaultAsyncDispatcher = Dispatchers.IO
            }
            every { context.applicationContext } returns context
            every { context.classLoader } returns javaClass.classLoader
            every { context.packageName } returns "io.github.santimattius.test"
            every { context.packageManager } returns packageManager
            val startupInitializer = AppStartupInitializer(context, Dispatchers.IO)
            stubAsyncManifest(ConcurrentTransientInitializer::class.java)
            var onDemand: Deferred<String>? = null

            try {
                startupInitializer.discoverAndInitialize(InitializationProvider::class.java)
                awaitConcurrentTransientCreates(1)
                val onDemandJob =
                    async(Dispatchers.IO) {
                        startupInitializer.doInitialize<String>(ConcurrentTransientInitializer::class.java)
                    }
                onDemand = onDemandJob
                awaitConcurrentTransientCreates(2)

                ConcurrentTransientInitializer.release.complete(Unit)
                onDemandJob.await()
                startupInitializer.awaitAllStartJobs()

                assertEquals(2, ConcurrentTransientInitializer.createCount.get())
            } finally {
                ConcurrentTransientInitializer.release.complete(Unit)
                boundedCleanup { onDemand?.await() }
                boundedCleanup { startupInitializer.awaitAllStartJobs() }
            }
        }

    @Test
    fun `startup graph does not join on-demand-owned transient in-flight work`() =
        runTest {
            AppStartupInitializer.configure {
                asyncInitializerStrategy = AsyncInitializerStrategy.Concurrent
                defaultAsyncDispatcher = Dispatchers.IO
            }
            every { context.applicationContext } returns context
            every { context.classLoader } returns javaClass.classLoader
            every { context.packageName } returns "io.github.santimattius.test"
            every { context.packageManager } returns packageManager
            val startupInitializer = AppStartupInitializer(context, Dispatchers.IO)
            stubAsyncManifest(ConcurrentTransientParentOne::class.java, ConcurrentTransientParentTwo::class.java)

            val onDemand =
                async(Dispatchers.IO) {
                    startupInitializer.doInitialize<String>(ConcurrentTransientInitializer::class.java)
                }
            try {
                awaitConcurrentTransientCreates(1)
                startupInitializer.discoverAndInitialize(InitializationProvider::class.java)
                awaitConcurrentTransientCreates(2)

                ConcurrentTransientInitializer.release.complete(Unit)
                onDemand.await()
                startupInitializer.awaitAllStartJobs()

                assertEquals(
                    "One on-demand value and one startup-graph value must be created",
                    2,
                    ConcurrentTransientInitializer.createCount.get(),
                )
            } finally {
                ConcurrentTransientInitializer.release.complete(Unit)
                boundedCleanup { onDemand.await() }
                boundedCleanup { startupInitializer.awaitAllStartJobs() }
            }
        }

    private suspend fun boundedCleanup(block: suspend () -> Unit) {
        runCatching { withTimeout(5_000) { block() } }
    }

    private suspend fun awaitConcurrentTransientCreates(expected: Int) {
        withTimeout(5_000) {
            while (ConcurrentTransientInitializer.createCount.get() < expected) delay(1)
        }
    }

    private fun stubAsyncManifest(vararg classes: Class<*>) {
        val metadata =
            Bundle().apply {
                classes.forEach { putString(it.name, "async-initializer") }
            }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = metadata }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    class RetainedSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "retained".also { createCount++ }

        override fun retainAfterStartup() = true

        companion object {
            var createCount = 0
        }
    }

    class TransientSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "transient".also { createCount++ }

        override fun retainAfterStartup() = false

        companion object {
            var createCount = 0
        }
    }

    class RetainedAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "retained".also { createCount++ }

        override fun retainAfterStartup() = true

        companion object {
            var createCount = 0
        }
    }

    class TransientAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "transient".also { createCount++ }

        override fun retainAfterStartup() = false

        companion object {
            var createCount = 0
        }
    }

    class TransientSharedDependency : StartupAsyncInitializer<String> {
        override fun dispatcher() = Dispatchers.IO

        override suspend fun create(context: Context): String {
            createCount.incrementAndGet()
            delay(100)
            return "transient-shared"
        }

        override fun retainAfterStartup() = false

        companion object {
            val createCount = AtomicInteger(0)
        }
    }

    class GatedStartupInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            started.complete(Unit)
            release.await()
            return "startup"
        }

        companion object {
            lateinit var started: CompletableDeferred<Unit>
            lateinit var release: CompletableDeferred<Unit>
        }
    }

    class ConcurrentTransientInitializer : StartupAsyncInitializer<String> {
        override fun dispatcher() = Dispatchers.IO

        override suspend fun create(context: Context): String {
            val instance = createCount.incrementAndGet()
            release.await()
            return "transient-$instance"
        }

        override fun retainAfterStartup() = false

        companion object {
            val createCount = AtomicInteger(0)
            lateinit var release: CompletableDeferred<Unit>
        }
    }

    class ConcurrentTransientParentOne : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(ConcurrentTransientInitializer::class.java)

        override suspend fun create(context: Context) = "one"
    }

    class ConcurrentTransientParentTwo : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(ConcurrentTransientInitializer::class.java)

        override suspend fun create(context: Context) = "two"
    }

    class TransientDependencyParentOne : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(TransientSharedDependency::class.java)

        override suspend fun create(context: Context) = "one"
    }

    class TransientDependencyParentTwo : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(TransientSharedDependency::class.java)

        override suspend fun create(context: Context) = "two"
    }
}

package io.github.santimattius.android.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Spec: startup-observability "Single instantiation per startup" (design D3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class InitializerRegistryTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        constructed.clear()
        executionOrder.clear()
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
    fun `peek does not consume and take does`() {
        val registry = InitializerRegistry()

        val peeked = registry.peek(Counted::class.java)
        assertSame(peeked, registry.peek(Counted::class.java))
        assertSame(peeked, registry.take(Counted::class.java))
        assertNotSame(peeked, registry.take(Counted::class.java))
        assertEquals(2, constructed[Counted::class.java.name]?.get())
    }

    @Test
    fun `concurrent peeks of the same class construct exactly one instance`() {
        val threads = 16
        repeat(5) {
            constructed.clear()
            val registry = InitializerRegistry()
            val barrier = CyclicBarrier(threads)
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val results = (1..threads).map {
                    pool.submit<Any> {
                        barrier.await()
                        registry.peek(SlowCounted::class.java)
                    }
                }.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(1, constructed[SlowCounted::class.java.name]?.get())
                assertEquals(1, results.map { System.identityHashCode(it) }.toSet().size)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `async startup creates exactly one instance per class with dependents and deferred priority`() {
        AppStartupInitializer.configure {
            asyncInitializerStrategy = AsyncInitializerStrategy.Validated
            firstFrameSignal = object : FirstFrameSignal {
                override suspend fun await() = Unit
            }
        }
        val initializer = AppStartupInitializer(context)
        stubManifest("async-initializer", AsyncLeaf::class.java, AsyncBase::class.java, AsyncDeferred::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        runBlocking {
            initializer.awaitAllStartJobs()
            initializer.coroutinesEngine.deferredStartJobs.awaitAll()
        }

        listOf(AsyncLeaf::class.java, AsyncBase::class.java, AsyncDeferred::class.java).forEach {
            assertEquals(it.name, 1, constructed[it.name]?.get())
        }
        assertEquals(3, initializer.metricsFlow.replayCache.size)
    }

    @Test
    fun `sync topological startup creates one instance per class and keeps dependency order`() {
        AppStartupInitializer.configure { syncOrderingStrategy = SyncOrderingStrategy.Topological }
        val initializer = AppStartupInitializer(context)
        stubManifest("sync-initializer", SyncDependent::class.java, SyncBase::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)

        assertEquals(1, constructed[SyncDependent::class.java.name]?.get())
        assertEquals(1, constructed[SyncBase::class.java.name]?.get())
        assertEquals(listOf("SyncBase", "SyncDependent"), executionOrder.toList())
    }

    private fun stubManifest(kind: String, vararg classes: Class<*>) {
        val bundle = Bundle().apply { classes.forEach { putString(it.name, kind) } }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
    }

    companion object {
        val constructed = ConcurrentHashMap<String, AtomicInteger>()
        val executionOrder = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun count(cls: Class<*>) = constructed.getOrPut(cls.name) { AtomicInteger() }.incrementAndGet()
    }

    class Counted {
        init { count(javaClass) }
    }

    class SlowCounted {
        // Widens the check-then-act window so a non-atomic peek reliably double-constructs.
        init {
            count(javaClass)
            Thread.sleep(20)
        }
    }

    class AsyncBase : StartupAsyncInitializer<String> {
        init { count(javaClass) }
        override fun priority() = StartupPriority.DEFERRED
        override suspend fun create(context: Context) = "base"
    }

    class AsyncLeaf : StartupAsyncInitializer<String> {
        init { count(javaClass) }
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> = listOf(AsyncBase::class.java)
        override suspend fun create(context: Context) = "leaf"
    }

    class AsyncDeferred : StartupAsyncInitializer<String> {
        init { count(javaClass) }
        override fun priority() = StartupPriority.DEFERRED
        override suspend fun create(context: Context) = "deferred"
    }

    class SyncBase : StartupSyncInitializer<String> {
        init { count(javaClass) }
        override fun create(context: Context): String = "base".also { executionOrder += "SyncBase" }
    }

    class SyncDependent : StartupSyncInitializer<String> {
        init { count(javaClass) }
        override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = listOf(SyncBase::class.java)
        override fun create(context: Context): String = "dep".also { executionOrder += "SyncDependent" }
    }
}

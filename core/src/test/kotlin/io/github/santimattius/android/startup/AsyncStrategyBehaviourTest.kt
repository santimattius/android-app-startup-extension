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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioural tests for [AsyncInitializerStrategy].
 *
 * Verifies end-to-end observable behaviour of both strategies:
 *
 * - [AsyncInitializerStrategy.Validated]: only root initializers get an explicit
 *   coroutine; their transitive async dependencies are resolved recursively inside
 *   that same coroutine. Every node in the graph is created exactly once.
 * - [AsyncInitializerStrategy.Concurrent]: every discovered initializer gets its
 *   own coroutine (current default behaviour, baseline for comparison).
 *
 * [StartupAsyncInitializer.syncDependencies] are intentionally omitted from all
 * graphs here — by the time async initializers start, all sync initializers have
 * already completed on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AsyncStrategyBehaviourTest {
    private lateinit var initializer: AppStartupInitializer
    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)

    @Before
    fun setup() {
        initializer = AppStartupInitializer(context)
        createCounts.clear()
        sharedDependencyCreates.set(0)
    }

    @After
    fun tearDown() {
        AppStartupInitializer.configure { }
    }

    // ── Validated: chain ──────────────────────────────────────────────────────

    @Test
    fun `Validated - full chain initialized when only root coroutine runs`() =
        runTest {
            val discovered = setOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)

            val roots = initializer.asyncValidateAndGetRoots(discovered)
            roots.forEach { root -> initializer.doInitialize<Any>(root) }

            assertEquals("A is the root — its coroutine must run create()", 1, createCounts["A"])
            assertEquals("B resolved recursively inside A's coroutine", 1, createCounts["B"])
            assertEquals("C resolved recursively inside B's resolution", 1, createCounts["C"])
        }

    @Test
    fun `Validated - roots count is 1 for a linear chain of 3`() {
        val discovered = setOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)

        val roots = initializer.asyncValidateAndGetRoots(discovered)

        assertEquals(
            "A linear chain has exactly one root (the tail node no one depends on)",
            1,
            roots.size,
        )
        assertEquals(ChainA::class.java, roots.single())
    }

    // ── Validated: diamond ────────────────────────────────────────────────────

    @Test
    fun `Validated - diamond fully initialized when single root coroutine runs`() =
        runTest {
            val discovered =
                setOf(
                    DiamondA::class.java,
                    DiamondB::class.java,
                    DiamondC::class.java,
                    DiamondD::class.java,
                )

            val roots = initializer.asyncValidateAndGetRoots(discovered)
            roots.forEach { root -> initializer.doInitialize<Any>(root) }

            assertEquals(1, createCounts["A"])
            assertEquals(1, createCounts["B"])
            assertEquals(1, createCounts["C"])
            assertEquals(
                "D is the shared leaf dep of B and C — must be created exactly once",
                1,
                createCounts["D"],
            )
        }

    @Test
    fun `Validated - diamond has exactly one root`() {
        val roots =
            initializer.asyncValidateAndGetRoots(
                setOf(DiamondA::class.java, DiamondB::class.java, DiamondC::class.java, DiamondD::class.java),
            )

        assertEquals(1, roots.size)
        assertEquals(DiamondA::class.java, roots.single())
    }

    // ── Validated: isolated nodes — all are roots ─────────────────────────────

    @Test
    fun `Validated - isolated nodes each get their own coroutine`() =
        runTest {
            val discovered = setOf(IsolatedX::class.java, IsolatedY::class.java)

            val roots = initializer.asyncValidateAndGetRoots(discovered)
            roots.forEach { root -> initializer.doInitialize<Any>(root) }

            assertEquals(
                "With no dependencies between them every node is a root",
                2,
                roots.size,
            )
            assertEquals(1, createCounts["X"])
            assertEquals(1, createCounts["Y"])
        }

    // ── Concurrent: all discovered initializers are launched ─────────────────

    @Test
    fun `Concurrent - all discovered initializers complete correctly`() =
        runTest {
            val discovered = setOf(ChainA::class.java, ChainB::class.java, ChainC::class.java)

            // Simulate Concurrent: launch a coroutine for every discovered initializer.
            // Each coroutine checks getOrPut so create() still runs only once per node.
            discovered.forEach { cls -> initializer.doInitialize<Any>(cls) }

            assertEquals(1, createCounts["A"])
            assertEquals(1, createCounts["B"])
            assertEquals(1, createCounts["C"])
        }

    @Test
    fun `Concurrent - circular roots fail before launching jobs`() {
        AppStartupInitializer.configure {
            asyncInitializerStrategy = AsyncInitializerStrategy.Concurrent
            defaultAsyncDispatcher = Dispatchers.IO
        }
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
        val concurrentInitializer = AppStartupInitializer(context, Dispatchers.IO)
        stubAsyncManifest(CyclicOne::class.java, CyclicTwo::class.java)

        val failure =
            runCatching {
                concurrentInitializer.discoverAndInitialize(InitializationProvider::class.java)
            }.exceptionOrNull()

        assertTrue(failure is StartupExtensionException)
        assertTrue(failure?.message?.contains("Cycle detected in async initializers") == true)
    }

    @Test
    fun `Concurrent - transitive cycle through undiscovered dependency fails before jobs launch`() {
        AppStartupInitializer.configure {
            asyncInitializerStrategy = AsyncInitializerStrategy.Concurrent
            defaultAsyncDispatcher = Dispatchers.IO
        }
        every { context.applicationContext } returns context
        every { context.classLoader } returns javaClass.classLoader
        every { context.packageName } returns "io.github.santimattius.test"
        every { context.packageManager } returns packageManager
        val concurrentInitializer = AppStartupInitializer(context, Dispatchers.IO)
        stubAsyncManifest(TransitiveCycleA::class.java, TransitiveCycleC::class.java)

        val failure =
            runCatching {
                concurrentInitializer.discoverAndInitialize(InitializationProvider::class.java)
            }.exceptionOrNull()

        assertTrue(failure is StartupExtensionException)
        assertTrue(failure?.message?.contains("Cycle detected in async initializers") == true)
        assertTrue("Validation must fail before any startup job is launched", concurrentInitializer.coroutinesEngine.startJobs.isEmpty())
        assertTrue("Validation must fail before any initializer is created", createCounts.isEmpty())
    }

    @Test
    fun `Concurrent - shared async dependency executes once across concurrent startup jobs`() =
        runTest {
            AppStartupInitializer.configure {
                asyncInitializerStrategy = AsyncInitializerStrategy.Concurrent
                defaultAsyncDispatcher = Dispatchers.IO
            }
            every { context.applicationContext } returns context
            every { context.classLoader } returns javaClass.classLoader
            every { context.packageName } returns "io.github.santimattius.test"
            every { context.packageManager } returns packageManager
            val concurrentInitializer = AppStartupInitializer(context, Dispatchers.IO)
            stubAsyncManifest(SharedDependencyParentOne::class.java, SharedDependencyParentTwo::class.java)

            concurrentInitializer.discoverAndInitialize(InitializationProvider::class.java)
            concurrentInitializer.awaitAllStartJobs()

            assertEquals(
                "Concurrent roots with one shared dependency must execute that dependency once",
                1,
                sharedDependencyCreates.get(),
            )
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

    companion object {
        val createCounts: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
        val sharedDependencyCreates = AtomicInteger(0)

        private fun record(name: String) = createCounts.merge(name, 1, Int::plus)
    }

    // Chain: C (no deps) → B → A
    private class ChainC : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("C")
            return "c"
        }
    }

    private class ChainB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("B")
            return "b"
        }

        override fun dependencies() = listOf(ChainC::class.java)
    }

    private class ChainA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("A")
            return "a"
        }

        override fun dependencies() = listOf(ChainB::class.java)
    }

    // Diamond: D (no deps), B → D, C → D, A → B + C
    private class DiamondD : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("D")
            return "d"
        }
    }

    private class DiamondB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("B")
            return "b"
        }

        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondC : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("C")
            return "c"
        }

        override fun dependencies() = listOf(DiamondD::class.java)
    }

    private class DiamondA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("A")
            return "a"
        }

        override fun dependencies() = listOf(DiamondB::class.java, DiamondC::class.java)
    }

    // Isolated: no dependencies between them
    private class IsolatedX : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("X")
            return "x"
        }
    }

    private class IsolatedY : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String {
            record("Y")
            return "y"
        }
    }

    private class CyclicOne : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(CyclicTwo::class.java)

        override suspend fun create(context: Context) = "one"
    }

    private class CyclicTwo : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(CyclicOne::class.java)

        override suspend fun create(context: Context) = "two"
    }

    private class TransitiveCycleA : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(TransitiveCycleB::class.java)

        override suspend fun create(context: Context): String {
            record("transitive-cycle-a")
            return "a"
        }
    }

    private class TransitiveCycleB : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(TransitiveCycleC::class.java)

        override suspend fun create(context: Context): String {
            record("transitive-cycle-b")
            return "b"
        }
    }

    private class TransitiveCycleC : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(TransitiveCycleA::class.java)

        override suspend fun create(context: Context): String {
            record("transitive-cycle-c")
            return "c"
        }
    }

    private class SharedDependency : StartupAsyncInitializer<String> {
        override fun dispatcher() = Dispatchers.IO

        override suspend fun create(context: Context): String {
            sharedDependencyCreates.incrementAndGet()
            delay(100)
            return "shared"
        }
    }

    private class SharedDependencyParentOne : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(SharedDependency::class.java)

        override suspend fun create(context: Context) = "one"
    }

    private class SharedDependencyParentTwo : StartupAsyncInitializer<String> {
        override fun dependencies() = listOf(SharedDependency::class.java)

        override suspend fun create(context: Context) = "two"
    }
}

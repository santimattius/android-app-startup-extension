package io.github.santimattius.android.startup.trace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.Bundle
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.InitializationProvider
import io.github.santimattius.android.startup.awaitAllStartJobs
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
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

/** Spec: startup-async-tracing P1 node/deps/permitWait/create sections (tasks 1.8-1.9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AsyncTraceSectionsTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val tracer = RecordingStartupTracer()

    private fun node(cls: Class<*>) = "Startup::${cls.name}"
    private fun phase(phase: String, cls: Class<*>) = "Startup::$phase::${cls.name}"

    @Before
    fun setUp() {
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
    fun `create is separated from the node wrapper and follows deps and permitWait`() = runBlocking {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        initializer.doInitialize<String>(Parent::class.java)

        val nodeBegin = tracer.asyncBeginIndex(node(Parent::class.java))
        val depsBegin = tracer.asyncBeginIndex(phase("deps", Parent::class.java))
        val depsEnd = tracer.asyncEndIndex(phase("deps", Parent::class.java))
        val permitEnd = tracer.asyncEndIndex(phase("permitWait", Parent::class.java))
        val createBegin = tracer.asyncBeginIndex(phase("create", Parent::class.java))
        val createEnd = tracer.asyncEndIndex(phase("create", Parent::class.java))
        val nodeEnd = tracer.asyncEndIndex(node(Parent::class.java))

        assertTrue(listOf(nodeBegin, depsBegin, depsEnd, permitEnd, createBegin, createEnd, nodeEnd).all { it >= 0 })
        assertTrue(nodeBegin < depsBegin && depsBegin < depsEnd)
        assertTrue(depsEnd < tracer.asyncBeginIndex(phase("permitWait", Parent::class.java)))
        assertTrue(permitEnd < createBegin && createBegin < createEnd && createEnd < nodeEnd)
        assertTrue(tracer.asyncBalanced())
    }

    @Test
    fun `dependencies are resolved before the parent waits for a permit at cap 1`() = runBlocking {
        AppStartupInitializer.configure { maxConcurrentAsyncInitializers = 1 }
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        initializer.doInitialize<String>(Parent::class.java)

        val childCreateEnd = tracer.asyncEndIndex(phase("create", Child::class.java))
        val parentPermitBegin = tracer.asyncBeginIndex(phase("permitWait", Parent::class.java))
        assertTrue(childCreateEnd in 0 until parentPermitBegin)
        // The child lives entirely inside the parent's deps section (I1).
        assertTrue(tracer.asyncEndIndex(node(Child::class.java)) < tracer.asyncEndIndex(phase("deps", Parent::class.java)))
    }

    @Test
    fun `names are fully qualified and wildcard compatible`() = runBlocking {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        initializer.doInitialize<String>(Parent::class.java)

        val names = tracer.events.map { it.name }
        assertTrue(names.all { it.startsWith("Startup::") && it.length <= 127 })
        assertTrue(names.contains(node(Parent::class.java)))
    }

    @Test
    fun `sections are balanced when create throws`() = runBlocking {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        runCatching { initializer.doInitialize<String>(Failing::class.java) }

        assertTrue(tracer.asyncBalanced())
        assertTrue(tracer.asyncEndIndex(phase("create", Failing::class.java)) >= 0)
        assertTrue(tracer.asyncEndIndex(node(Failing::class.java)) >= 0)
    }

    @Test
    fun `sections are balanced when the initializer is cancelled`() = runBlocking {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        runCatching { initializer.doInitialize<String>(Cancelling::class.java) }

        assertTrue(tracer.asyncBalanced())
        assertTrue(tracer.asyncEndIndex(node(Cancelling::class.java)) >= 0)
    }

    @Test
    fun `permitWait of the second initializer at cap 1 ends before its create and after the first create`() {
        AppStartupInitializer.configure { maxConcurrentAsyncInitializers = 1 }
        val initializer = AppStartupInitializer(context, Dispatchers.IO, tracer)
        stubAsyncManifest(Slow1::class.java, Slow2::class.java)

        initializer.discoverAndInitialize(InitializationProvider::class.java)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (tracer.events.count { it is RecordingStartupTracer.Event.AsyncBegin && it.name.startsWith("Startup::permitWait::") } < 2 &&
            System.nanoTime() < deadline
        ) Thread.sleep(5)
        gate.countDown()
        runBlocking { initializer.awaitAllStartJobs() }

        val classes = listOf(Slow1::class.java, Slow2::class.java)
            .sortedBy { tracer.asyncBeginIndex(phase("create", it)) }
        val (first, second) = classes
        assertTrue(tracer.asyncEndIndex(phase("permitWait", first)) < tracer.asyncBeginIndex(phase("create", first)))
        assertTrue(tracer.asyncEndIndex(phase("permitWait", second)) < tracer.asyncBeginIndex(phase("create", second)))
        assertTrue(tracer.asyncEndIndex(phase("permitWait", second)) > tracer.asyncEndIndex(phase("create", first)))
        assertTrue(tracer.asyncBalanced())
    }

    @Test
    fun `sync node deps and create sections are balanced and ordered`() {
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        initializer.doInitialize<String>(SyncParent::class.java)

        val depsBegin = tracer.syncBeginIndex(phase("deps", SyncParent::class.java))
        val depsEnd = tracer.syncEndIndex(phase("deps", SyncParent::class.java))
        val createBegin = tracer.syncBeginIndex(phase("create", SyncParent::class.java))
        assertTrue(tracer.syncBeginIndex(node(SyncParent::class.java)) in 0 until depsBegin)
        assertTrue(tracer.syncBeginIndex(node(SyncChild::class.java)) in depsBegin until depsEnd)
        assertTrue(depsEnd < createBegin)
        assertTrue(createBegin < tracer.syncEndIndex(node(SyncParent::class.java)))
        assertTrue(tracer.syncBalanced())
    }

    @Test
    fun `disabled tracer records nothing and results are unchanged`() = runBlocking {
        tracer.enabled = false
        val initializer = AppStartupInitializer(context, Dispatchers.Default, tracer)

        val result = initializer.doInitialize<String>(Parent::class.java)

        assertEquals("parent(child)", result)
        assertTrue(tracer.events.isEmpty())
    }

    private fun stubAsyncManifest(vararg classes: Class<*>) {
        val bundle = Bundle().apply { classes.forEach { putString(it.name, "async-initializer") } }
        every {
            packageManager.getProviderInfo(any<ComponentName>(), any<Int>())
        } returns ProviderInfo().apply { metaData = bundle }
    }

    companion object {
        @Volatile
        var gate = CountDownLatch(1)
    }

    class Child : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "child"
    }

    class Parent : StartupAsyncInitializer<String> {
        override fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> = listOf(Child::class.java)
        override suspend fun create(context: Context) = "parent(child)"
    }

    class Failing : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = error("boom")
    }

    class Cancelling : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String =
            throw kotlinx.coroutines.CancellationException("cancelled")
    }

    class Slow1 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "s1".also { gate.await(5, TimeUnit.SECONDS) }
    }

    class Slow2 : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context): String = "s2".also { gate.await(5, TimeUnit.SECONDS) }
    }

    class SyncChild : StartupSyncInitializer<String> {
        override fun create(context: Context) = "c"
    }

    class SyncParent : StartupSyncInitializer<String> {
        override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = listOf(SyncChild::class.java)
        override fun create(context: Context) = "p"
    }
}

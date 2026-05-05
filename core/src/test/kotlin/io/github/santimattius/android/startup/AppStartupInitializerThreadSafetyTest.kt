package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ConcurrentModificationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppStartupInitializerThreadSafetyTest {

    private val context: Context = mockk(relaxed = true)
    private val initializer = AppStartupInitializer(context)

    @Test
    fun `syncDiscovered and asyncDiscovered survive concurrent reads during iteration`() {
        // Given: access the internal sets via reflection and pre-populate them
        val syncField = AppStartupInitializer::class.java.getDeclaredField("syncDiscovered")
        syncField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val syncSet = syncField.get(initializer) as MutableSet<Class<out StartupSyncInitializer<*>>>

        val asyncField = AppStartupInitializer::class.java.getDeclaredField("asyncDiscovered")
        asyncField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val asyncSet = asyncField.get(initializer) as MutableSet<Class<out StartupAsyncInitializer<*>>>

        syncSet.add(FakeSyncA::class.java)
        syncSet.add(FakeSyncB::class.java)
        asyncSet.add(FakeAsyncA::class.java)
        asyncSet.add(FakeAsyncB::class.java)

        val executor = Executors.newFixedThreadPool(8)
        val startLatch = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()

        // When: multiple threads concurrently write to and iterate the sets
        val futures = (1..40).map { i ->
            executor.submit {
                try {
                    startLatch.await()
                    if (i % 2 == 0) {
                        syncSet.add(FakeSyncA::class.java)
                        asyncSet.add(FakeAsyncA::class.java)
                    } else {
                        syncSet.forEach { _ -> }
                        asyncSet.forEach { _ -> }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }

        startLatch.countDown()
        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        executor.shutdown()

        // Then: no ConcurrentModificationException is thrown
        assertTrue(
            "Expected no ConcurrentModificationException but got: ${errors.map { it::class.simpleName }}",
            errors.none { it is ConcurrentModificationException }
        )
    }

    private class FakeSyncA : StartupSyncInitializer<String> {
        override fun create(context: Context) = "a"
    }

    private class FakeSyncB : StartupSyncInitializer<String> {
        override fun create(context: Context) = "b"
    }

    private class FakeAsyncA : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "a"
    }

    private class FakeAsyncB : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "b"
    }
}

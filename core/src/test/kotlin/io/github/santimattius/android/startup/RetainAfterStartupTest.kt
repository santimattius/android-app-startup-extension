package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainAfterStartupTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var initializer: AppStartupInitializer

    @Before
    fun setup() {
        initializer = AppStartupInitializer(context)
        RetainedSyncInitializer.createCount = 0
        TransientSyncInitializer.createCount = 0
        RetainedAsyncInitializer.createCount = 0
        TransientAsyncInitializer.createCount = 0
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
    fun `retained async initializer runs create() once across multiple calls`() = runTest {
        initializer.doInitialize<String>(RetainedAsyncInitializer::class.java)
        initializer.doInitialize<String>(RetainedAsyncInitializer::class.java)

        assertEquals(1, RetainedAsyncInitializer.createCount)
    }

    @Test
    fun `transient async initializer re-runs create() on every call`() = runTest {
        initializer.doInitialize<String>(TransientAsyncInitializer::class.java)
        initializer.doInitialize<String>(TransientAsyncInitializer::class.java)

        assertEquals(2, TransientAsyncInitializer.createCount)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    class RetainedSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "retained".also { createCount++ }
        override fun retainAfterStartup() = true
        companion object { var createCount = 0 }
    }

    class TransientSyncInitializer : StartupSyncInitializer<String> {
        override fun create(context: Context) = "transient".also { createCount++ }
        override fun retainAfterStartup() = false
        companion object { var createCount = 0 }
    }

    class RetainedAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "retained".also { createCount++ }
        override fun retainAfterStartup() = true
        companion object { var createCount = 0 }
    }

    class TransientAsyncInitializer : StartupAsyncInitializer<String> {
        override suspend fun create(context: Context) = "transient".also { createCount++ }
        override fun retainAfterStartup() = false
        companion object { var createCount = 0 }
    }
}

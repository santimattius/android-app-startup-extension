package io.github.santimattius.android.startup

import android.content.Context
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec: bounded-async-concurrency (task 4.3) — verifies [AppStartupInitializer] wires
 * [AppStartupConfig.maxConcurrentAsyncInitializers] into the engine's `cap` at construction
 * time.
 *
 * This is intentionally additive/inert: the engine's `acquireAsyncPermit()`/`releaseAsyncPermit()`
 * primitives are not yet invoked from `doAsyncInitialize`'s hot path (that wiring is a separate,
 * later change) — so this test only confirms the cap value reaches the engine, not any runtime
 * throttling behavior.
 */
class AsyncConcurrencyCapWiringTest {

    private val context: Context = mockk(relaxed = true)

    @After
    fun resetConfig() {
        AppStartupInitializer.configure { }
    }

    @Test
    fun `engine cap defaults to null when maxConcurrentAsyncInitializers is not configured`() {
        val initializer = AppStartupInitializer(context)

        assertNull(readEngineCap(initializer))
    }

    @Test
    fun `engine cap reflects the configured maxConcurrentAsyncInitializers`() {
        AppStartupInitializer.configure { maxConcurrentAsyncInitializers = 2 }

        val initializer = AppStartupInitializer(context)

        assertEquals(2, readEngineCap(initializer))
    }

    private fun readEngineCap(initializer: AppStartupInitializer): Int? {
        val field = initializer.coroutinesEngine.javaClass.getDeclaredField("cap")
        field.isAccessible = true
        return field.get(initializer.coroutinesEngine) as Int?
    }
}

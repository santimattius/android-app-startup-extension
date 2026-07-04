package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the [StartupAsyncInitializer] interface defaults.
 *
 * Task 5.2 (ADR-3) changes the default [StartupAsyncInitializer.dispatcher] return type to a
 * nullable sentinel (`CoroutineDispatcher? = null`) so the library can distinguish
 * "unoverridden" (defer to `config.defaultAsyncDispatcher`) from an explicit per-instance choice.
 */
class StartupAsyncInitializerTest {

    @Test
    fun `default dispatcher() returns null sentinel`() {
        val initializer = object : StartupAsyncInitializer<String> {
            override suspend fun create(context: Context): String = "x"
        }

        assertNull(
            "Default dispatcher() must return null so the library can apply its own default",
            initializer.dispatcher(),
        )
    }

    @Test
    fun `overridden dispatcher() returns the declared dispatcher`() {
        val initializer = object : StartupAsyncInitializer<String> {
            override fun dispatcher(): CoroutineDispatcher = Dispatchers.IO
            override suspend fun create(context: Context): String = "x"
        }

        assertEquals(Dispatchers.IO, initializer.dispatcher())
    }
}

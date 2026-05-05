package io.github.santimattius.android.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupExtensionExceptionTest {

    @Test
    fun `StartupExtensionException is catchable as Exception`() {
        val result = runCatching<Unit> { throw StartupExtensionException("test") }

        assertTrue(result.exceptionOrNull() is Exception)
    }

    @Test
    fun `StartupExtensionException preserves original cause`() {
        val cause = IllegalStateException("root cause")
        val exception = StartupExtensionException(cause)

        assertEquals(cause, exception.cause)
    }
}

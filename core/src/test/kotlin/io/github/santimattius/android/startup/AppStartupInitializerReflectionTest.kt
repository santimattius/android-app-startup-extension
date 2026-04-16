package io.github.santimattius.android.startup

import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartupInitializerReflectionTest {

    private val context: Context = mockk(relaxed = true)
    private val initializer = AppStartupInitializer(context)

    @Test
    fun `registering an abstract sync class throws StartupExtensionException with clear message`() {
        abstract class AbstractSyncInitializer : StartupSyncInitializer<String>

        val exception = assertThrows(StartupExtensionException::class.java) {
            initializer.doInitialize<String>(AbstractSyncInitializer::class.java)
        }

        assertTrue(
            "Expected message to mention 'abstract' or 'concrete', got: ${exception.message}",
            exception.message!!.contains("abstract") || exception.message!!.contains("concrete")
        )
    }

    @Test
    fun `registering an abstract async class throws StartupExtensionException with clear message`() = runTest {
        abstract class AbstractAsyncInitializer : StartupAsyncInitializer<String>

        var exception: StartupExtensionException? = null
        try {
            initializer.doInitialize<String>(AbstractAsyncInitializer::class.java)
        } catch (e: StartupExtensionException) {
            exception = e
        }

        assertNotNull("Expected StartupExtensionException to be thrown", exception)
        assertTrue(
            "Expected message to mention 'abstract' or 'concrete', got: ${exception?.message}",
            exception!!.message!!.contains("abstract") || exception.message!!.contains("concrete")
        )
    }
}

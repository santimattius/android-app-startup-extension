package com.santimattius.android.sample.scenario

import android.app.Application
import android.os.Build
import io.github.santimattius.android.startup.AppStartupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], application = Application::class)
class StartupScenarioTest {

    private fun builder(s: StartupScenario) = AppStartupConfig.Builder().also { s.settings.applyTo(it) }

    @Test
    fun `scenarios map to the documented options`() {
        assertEquals(1, builder(StartupScenario.CapOneIo).maxConcurrentAsyncInitializers)
        assertEquals(3_000L, builder(StartupScenario.DeferredTimeout).deferredStartupTimeoutMs)
        assertTrue(builder(StartupScenario.Instrumented).dispatcherInstrumentationEnabled)
        assertEquals(2, builder(StartupScenario.ReplayTwo).metricsReplayCapacity)
        assertEquals(1, builder(StartupScenario.StrictMode).strictModeConcurrencyThreshold)
        // The strict-mode warning is only logged with debug logging on.
        assertTrue(builder(StartupScenario.StrictMode).debugLoggingEnabled)
    }

    @Test
    fun `unknown names fall back to Default and every scenario is documented`() {
        assertEquals(StartupScenario.Default, StartupScenario.fromName("gone"))
        StartupScenario.entries.forEach {
            assertTrue(it.title.isNotBlank() && it.description.isNotBlank() && it.observe.isNotBlank())
        }
    }

    @Test
    fun `the selection survives in preferences`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(StartupScenario.Default, ScenarioStore.selected(context))
        ScenarioStore.select(context, StartupScenario.ReplayTwo)
        assertEquals(StartupScenario.ReplayTwo, ScenarioStore.selected(context))
        ScenarioStore.select(context, StartupScenario.Default)
    }
}

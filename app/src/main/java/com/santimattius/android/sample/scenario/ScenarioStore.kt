package com.santimattius.android.sample.scenario

import android.content.Context
import com.santimattius.android.sample.usecases.SampleStartup

/** Persists the selected scenario and applies it at process start. Debug builds only. */
object ScenarioStore {
    private const val PREFS = "startup_scenarios"
    private const val KEY = "selected"

    /** Scenario this process was started with; set once by [applyAtStartup]. */
    @Volatile
    var applied: StartupScenario = StartupScenario.Default
        private set

    fun selected(context: Context): StartupScenario =
        StartupScenario.fromName(prefs(context).getString(KEY, null))

    fun select(context: Context, scenario: StartupScenario) {
        // commit(): the process is usually killed right after, so apply() could lose the write.
        prefs(context).edit().putString(KEY, scenario.name).commit()
    }

    /** Call from `Application.attachBaseContext`, before the provider runs. */
    fun applyAtStartup(context: Context) {
        applied = selected(context)
        // Default must not call configure at all, to match the untouched startup path.
        if (applied != StartupScenario.Default) SampleStartup.apply(applied.settings)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

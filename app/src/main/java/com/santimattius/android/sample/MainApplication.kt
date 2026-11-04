package com.santimattius.android.sample

import android.app.Application
import android.content.Context
import android.util.Log
import com.santimattius.android.sample.scenario.ScenarioStore
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.onAppStartupLaunched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Debug-only: BuildConfig.DEBUG is a compile-time false in release and benchmarkRelease,
        // so R8 drops this branch and the release startup path stays untouched.
        if (BuildConfig.DEBUG) ScenarioStore.applyAtStartup(base)
    }

    override fun onCreate() {
        super.onCreate()
        val appStartupInitializer = AppStartupInitializer.getInstance(this@MainApplication)

        CoroutineScope(Dispatchers.Main).launch {
            appStartupInitializer.onAppStartupLaunched {
                Log.d("MainApplication", "AppStartupLaunched")
            }
        }
    }
}
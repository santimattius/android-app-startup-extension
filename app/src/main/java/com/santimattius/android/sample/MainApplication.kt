package com.santimattius.android.sample

import android.app.Application
import android.util.Log
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.onAppStartupLaunched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainApplication : Application() {

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
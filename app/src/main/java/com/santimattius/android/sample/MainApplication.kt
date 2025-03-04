package com.santimattius.android.sample

import android.app.Application
import android.util.Log
import io.github.santimattius.android.startup.AppInitializer
import io.github.santimattius.android.startup.onAppStartupLaunched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val appInitializer = AppInitializer.getInstance(this@MainApplication)

        CoroutineScope(Dispatchers.Main).launch {
            appInitializer.onAppStartupLaunched {
                Log.d("MainApplication", "AppStartupLaunched")
            }
        }

    }
}
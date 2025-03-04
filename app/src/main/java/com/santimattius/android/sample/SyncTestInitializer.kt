package com.santimattius.android.sample

import android.content.Context
import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer

class SyncTestInitializer : StartupSyncInitializer<Unit> {

    override fun create(context: Context) {
        Log.d("SyncTestInitializer", "TestInitializer created")
    }
}
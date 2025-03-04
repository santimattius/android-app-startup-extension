package com.santimattius.android.sample

import android.content.Context
import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import kotlinx.coroutines.delay

class AsyncTestInitializer : StartupAsyncInitializer<Unit> {

    override suspend fun create(context: Context) {
        delay(5000)
        Log.d("AsyncTestInitializer", "TestInitializer created")
    }
}
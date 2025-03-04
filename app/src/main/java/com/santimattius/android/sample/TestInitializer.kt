package com.santimattius.android.sample

import android.content.Context
import android.util.Log
import io.github.santimattius.android.startup.StartupInitializer

class TestInitializer : StartupInitializer<Unit> {

    override fun create(context: Context) {
        Log.d("TestInitializer", "TestInitializer created")
    }
}
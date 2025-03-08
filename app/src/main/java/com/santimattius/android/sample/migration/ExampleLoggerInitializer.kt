package com.santimattius.android.sample.migration

import android.content.Context
import androidx.startup.Initializer

// Initializes ExampleLogger.
class ExampleLoggerInitializer : Initializer<Unit> {
    override fun create(context: Context): Unit {
        // Code to initialize ExampleLogger.
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // Defines a dependency on.
        return emptyList()
    }
}
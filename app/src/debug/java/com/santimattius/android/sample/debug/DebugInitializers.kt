package com.santimattius.android.sample.debug

import android.content.Context
import android.util.Log
import io.github.santimattius.android.startup.StartupPriority
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import kotlinx.coroutines.delay

/**
 * Debug-only startup initializers (declared in app/src/debug/AndroidManifest.xml) that give the
 * startup scenarios something visible to act on. They do not exist in release builds.
 */
abstract class DebugAsyncBase(private val delayMs: Long) : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) {
        Log.d("DebugInitializer", "${javaClass.simpleName} start")
        delay(delayMs)
        Log.d("DebugInitializer", "${javaClass.simpleName} end")
    }
}

class DebugNormalAInitializer : DebugAsyncBase(1_500)
class DebugNormalBInitializer : DebugAsyncBase(1_500)
class DebugNormalCInitializer : DebugAsyncBase(1_500)

class DebugDeferredInitializer : DebugAsyncBase(1_000) {
    override fun priority() = StartupPriority.DEFERRED
}

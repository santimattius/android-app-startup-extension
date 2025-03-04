package io.github.santimattius.android.startup.engine

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.coroutines.CoroutineContext

class AppStartupCoroutinesEngine(coroutineDispatcher: CoroutineDispatcher? = null) : CoroutineScope {
    private var dispatcher: CoroutineDispatcher = coroutineDispatcher ?: Dispatchers.Default
    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() =  supervisorJob + dispatcher

    internal val startJobs = arrayListOf<Deferred<*>>()

    fun <T> launchStartJob(block: suspend CoroutineScope.() -> T) {
        startJobs.add(async { block() })
    }

    suspend fun awaitAllStartJobs() {
        Log.d("", "$TAG - await All Start Jobs ...")
        startJobs.awaitAll()
        startJobs.clear()
    }

    companion object {
        const val TAG = "[CoroutinesEngine]"
        const val EXTENSION_NAME = "coroutine-engine"
    }
}
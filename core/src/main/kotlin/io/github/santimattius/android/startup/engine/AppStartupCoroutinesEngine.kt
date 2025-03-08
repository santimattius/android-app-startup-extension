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

/**
 * `AppStartupCoroutinesEngine` is a utility class designed to manage and execute a collection of asynchronous startup tasks
 * using Kotlin coroutines. It provides a mechanism to launch multiple jobs concurrently and wait for their completion.
 *
 * This class utilizes a `SupervisorJob` to ensure that the failure of one job doesn't affect the execution of other jobs.
 * It also allows specifying a custom `CoroutineDispatcher` for fine-grained control over thread management.
 *
 * Key Features:
 * - **Concurrent Execution:** Launches multiple startup jobs concurrently.
 * - **Job Management:** Keeps track of all launched jobs.
 * - **Error Isolation:** Employs `SupervisorJob` to isolate job failures.
 * - **Custom Dispatcher:** Allows using a custom `CoroutineDispatcher` or defaults to `Dispatchers.Default`.
 * - **Deferred Result Handling:** Utilizes `Deferred` to represent the result of each job, enabling waiting for results if needed (though currently only awaitAll is used)
 * - **Clean Up:** Clears the list of jobs after all have completed.
 *
 * Usage:
 * 1. Create an instance of `AppStartupCoroutinesEngine`.
 * 2. Use `launchStartJob` to add startup tasks (coroutines).
 * 3. Call `awaitAllStartJobs` to wait for all tasks to complete.
 *
 * @property dispatcher The `CoroutineDispatcher` used for launching coroutines. Defaults to `Dispatchers.Default` if not provided.
 * @property supervisorJob A `SupervisorJob` that manages the lifecycle of all launched jobs.
 * @property coroutineContext The combined `CoroutineContext` composed of the `supervisorJob` and `dispatcher`.
 * @property startJobs An `ArrayList` holding `Deferred` instances representing the launched startup jobs.
 */
internal class AppStartupCoroutinesEngine(coroutineDispatcher: CoroutineDispatcher? = null) : CoroutineScope {

    private var dispatcher: CoroutineDispatcher = coroutineDispatcher ?: Dispatchers.Default
    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() =  supervisorJob + dispatcher

    internal val startJobs = arrayListOf<Deferred<*>>()

    fun <T> launchStartJob(block: suspend CoroutineScope.() -> T) {
        startJobs.add(async { block() })
    }

    suspend fun awaitAllStartJobs() {
        Log.d(EXTENSION_NAME, "$TAG - await All Start Jobs ...")
        startJobs.awaitAll()
        startJobs.clear()
    }

    companion object {
        const val TAG = "[CoroutinesEngine]"
        const val EXTENSION_NAME = "AppStartupCoroutinesEngine"
    }
}
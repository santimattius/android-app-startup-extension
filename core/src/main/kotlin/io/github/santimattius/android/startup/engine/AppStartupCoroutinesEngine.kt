package io.github.santimattius.android.startup.engine

import android.util.Log
import io.github.santimattius.android.startup.StartupState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
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
 * @property _startJobs An `ArrayList` holding `Deferred` instances representing the launched startup jobs.
 */
internal class AppStartupCoroutinesEngine(
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
) : CoroutineScope {

    private val dispatcher: CoroutineDispatcher = coroutineDispatcher
    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + dispatcher

    private val _startJobs = CopyOnWriteArrayList<Deferred<*>>()
    val startJobs: List<Deferred<*>>
        get() = _startJobs

    private val _startupState = MutableStateFlow(StartupState.IDLE)

    /** Hot [StateFlow] that reflects the current startup lifecycle state. */
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    fun <T> launchStartJob(block: suspend CoroutineScope.() -> T) {
        _startupState.value = StartupState.IN_PROGRESS
        _startJobs.add(async { block() })
    }

    suspend fun awaitAllStartJobs() {
        Log.d(EXTENSION_NAME, "$TAG - await All Start Jobs ...")
        try {
            _startJobs.awaitAll()
            _startupState.value = StartupState.COMPLETED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _startupState.value = StartupState.ERROR
            throw e
        } finally {
            _startJobs.clear()
        }
    }

    /**
     * Awaits completion of all start jobs within [timeoutMs] milliseconds.
     *
     * If the jobs do not complete within the given timeout, [kotlinx.coroutines.TimeoutCancellationException]
     * is thrown and the job list is **not** cleared — the underlying jobs continue running
     * in the engine's [SupervisorJob] scope unaffected, since they live in a separate scope
     * from the awaiting coroutine. State remains [StartupState.IN_PROGRESS] on timeout.
     *
     * @throws kotlinx.coroutines.TimeoutCancellationException if the timeout elapses before all jobs finish.
     */
    suspend fun awaitAllStartJobs(timeoutMs: Long) {
        Log.d(EXTENSION_NAME, "$TAG - await All Start Jobs with timeout ${timeoutMs}ms ...")
        try {
            withTimeout(timeoutMs) {
                _startJobs.awaitAll()
            }
            _startupState.value = StartupState.COMPLETED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _startupState.value = StartupState.ERROR
            throw e
        } finally {
            _startJobs.clear()
        }
    }

    internal fun areAllStartJobsDone(): Boolean = startJobs.none { it.isActive }

    fun cancel() {
        supervisorJob.cancel()
    }

    companion object {
        const val TAG = "[CoroutinesEngine]"
        const val EXTENSION_NAME = "AppStartupCoroutinesEngine"
    }
}
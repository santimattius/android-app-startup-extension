package io.github.santimattius.android.startup.engine

import android.util.Log
import io.github.santimattius.android.startup.StartupState
import io.github.santimattius.android.startup.trace.StartupTraceNames
import io.github.santimattius.android.startup.trace.StartupTracer
import io.github.santimattius.android.startup.trace.SystemStartupTracer
import io.github.santimattius.android.startup.trace.closeAsyncSection
import io.github.santimattius.android.startup.trace.mark
import io.github.santimattius.android.startup.trace.openAsyncSection
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
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
 * @property _startJobs A synchronized [ArrayList] holding [Deferred] instances for launched startup jobs.
 *   Writes happen only during discovery (single-threaded) and reads only during await — no concurrent
 *   access in practice, but synchronized for correctness guarantees.
 * @param cap Optional cap on the number of async `create()` bodies allowed to execute concurrently.
 *   `null` (default) means unbounded — current, unchanged behavior. When set, backs an internal
 *   [Semaphore] consumed via [acquireAsyncPermit]/[releaseAsyncPermit]. These primitives are
 *   additive/inert on their own: they only bound concurrency once a caller actually invokes them
 *   around the work being gated.
 */
internal class AppStartupCoroutinesEngine(
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cap: Int? = null,
    private val tracer: StartupTracer = SystemStartupTracer,
) : CoroutineScope {
    private val dispatcher: CoroutineDispatcher = coroutineDispatcher
    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + dispatcher

    private val _startJobs: MutableList<Deferred<*>> = Collections.synchronizedList(ArrayList())
    val startJobs: List<Deferred<*>>
        get() = _startJobs

    /**
     * Jobs for [StartupPriority.DEFERRED] roots, tracked SEPARATELY from [_startJobs] so the
     * critical-path [awaitAllStartJobs] never awaits them. This is what keeps a `runBlocking`
     * consumer (or cap=1) from deadlocking on work that is intentionally suspended on the
     * first-frame signal.
     *
     * @see io.github.santimattius.android.startup.StartupPriority.DEFERRED
     */
    private val _deferredStartJobs: MutableList<Deferred<*>> = Collections.synchronizedList(ArrayList())

    /** Read-only view of the currently tracked deferred start jobs (for tests/diagnostics). */
    val deferredStartJobs: List<Deferred<*>>
        get() = _deferredStartJobs

    private val _startupState = MutableStateFlow(StartupState.IDLE)

    /** Hot [StateFlow] that reflects the current startup lifecycle state. */
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    /** Snapshot of how many async `create()` bodies are currently active. Written via [enterActive]/[exitActive]. */
    private val activeAsync = AtomicInteger(0)

    /** Backs [acquireAsyncPermit]/[releaseAsyncPermit]. `null` when [cap] is `null` (unbounded). */
    private val semaphore: Semaphore? = cap?.let(::Semaphore)

    /** Callers currently suspended in [acquireAsyncPermit]; only used for the trace counter. */
    private val permitsWaiting = AtomicInteger(0)

    /** Serializes read+emit so a stale counter value can never be recorded after a newer one. */
    private val counterLock = Any()

    private inline fun emitCounter(
        name: String,
        value: () -> Int,
    ) {
        if (tracer.isAsyncEnabled) synchronized(counterLock) { tracer.setCounter(name, value().toLong()) }
    }

    /** Publishes [state] and, only on an actual transition, records a zero-length marker. */
    private fun transitionTo(state: StartupState) {
        val previousState =
            _startupState.getAndUpdate { currentState ->
                if (currentState == StartupState.ERROR && state == StartupState.COMPLETED) currentState else state
            }
        val nextState =
            if (previousState == StartupState.ERROR && state == StartupState.COMPLETED) {
                previousState
            } else {
                state
            }
        if (previousState == nextState) return
        tracer.mark { StartupTraceNames.of("marker::${nextState.name}", null) }
    }

    /**
     * Retains failed jobs while any sibling is active, so their ownership remains observable until
     * the complete startup batch reaches a terminal state.
     */
    private fun clearStartJobsWhenAllTerminal() {
        synchronized(_startJobs) {
            if (_startJobs.all { it.isCompleted }) _startJobs.clear()
        }
    }

    private fun markCancel() = tracer.mark { StartupTraceNames.of("marker::cancel", null) }

    fun <T> launchStartJob(block: suspend CoroutineScope.() -> T) {
        transitionTo(StartupState.IN_PROGRESS)
        _startJobs.add(async { block() })
    }

    /**
     * Launches a DEFERRED start job as an `async` child of the engine's [SupervisorJob], tracked in
     * [_deferredStartJobs] (NOT [_startJobs]).
     *
     * The caller is expected to gate [block] on the first-frame signal (with a timeout fallback)
     * before doing any real work. Because these jobs are excluded from [awaitAllStartJobs], the
     * critical-path await returns without waiting for them — no deadlock at cap=1 or under
     * `runBlocking`. As a [SupervisorJob] child, a failing deferred job is isolated from siblings and
     * — being never awaited — never surfaces as an unhandled-exception crash.
     *
     * This is a callable primitive only; it does not itself touch the first-frame signal.
     */
    fun <T> launchDeferredStartJob(block: suspend CoroutineScope.() -> T) {
        _deferredStartJobs.add(async { block() })
    }

    /**
     * Increments the active-async counter and returns the resulting snapshot.
     *
     * Call immediately before starting an async `create()` body; pair with a matching
     * [exitActive] call in a `finally` block so the counter never leaks.
     *
     * @return the active count immediately after this increment — the concurrency snapshot
     *   to attach to the corresponding `StartupMetric`.
     */
    fun enterActive(): Int = activeAsync.incrementAndGet().also { emitCounter(ACTIVE_ASYNC) { activeAsync.get() } }

    /** Decrements the active-async counter. Must be paired with a prior [enterActive] call. */
    fun exitActive() {
        activeAsync.decrementAndGet()
        emitCounter(ACTIVE_ASYNC) { activeAsync.get() }
    }

    /**
     * Suspends until a permit is available when a concurrency [cap] is configured.
     *
     * No-op — never suspends — when [cap] is `null` (unbounded, default/current behavior).
     * Must be paired with a matching [releaseAsyncPermit] call in a `finally` block.
     */
    suspend fun acquireAsyncPermit() {
        val semaphore = semaphore ?: return
        if (!semaphore.tryAcquire()) {
            permitsWaiting.incrementAndGet()
            emitCounter(PERMITS_WAITING) { permitsWaiting.get() }
            try {
                semaphore.acquire()
            } finally {
                permitsWaiting.decrementAndGet()
                emitCounter(PERMITS_WAITING) { permitsWaiting.get() }
            }
        }
        emitCounter(PERMITS_AVAILABLE) { semaphore.availablePermits }
    }

    /** Releases a permit previously obtained via [acquireAsyncPermit]. No-op when [cap] is `null`. */
    fun releaseAsyncPermit() {
        val semaphore = semaphore ?: return
        semaphore.release()
        emitCounter(PERMITS_AVAILABLE) { semaphore.availablePermits }
    }

    suspend fun awaitAllStartJobs() {
        Log.d(EXTENSION_NAME, "$TAG - await All Start Jobs ...")
        val span = tracer.openAsyncSection { StartupTraceNames.of("awaitAll", null) }
        try {
            _startJobs.awaitAll()
            transitionTo(StartupState.COMPLETED)
        } catch (e: CancellationException) {
            markCancel()
            throw e
        } catch (e: Throwable) {
            transitionTo(StartupState.ERROR)
            throw e
        } finally {
            clearStartJobsWhenAllTerminal()
            tracer.closeAsyncSection(span)
        }
    }

    /**
     * Awaits completion of all start jobs within [timeoutMs] milliseconds.
     *
     * If the jobs do not complete within the given timeout, [kotlinx.coroutines.TimeoutCancellationException]
     * is thrown. Any jobs still running at that point are explicitly cancelled to avoid zombie
     * coroutines consuming CPU and I/O in the background with no reference to clean them up.
     *
     * @throws kotlinx.coroutines.TimeoutCancellationException if the timeout elapses before all jobs finish.
     */
    suspend fun awaitAllStartJobs(timeoutMs: Long) {
        Log.d(EXTENSION_NAME, "$TAG - await All Start Jobs with timeout ${timeoutMs}ms ...")
        val span = tracer.openAsyncSection { StartupTraceNames.of("awaitAll", null) }
        try {
            withTimeout(timeoutMs) {
                _startJobs.awaitAll()
            }
            transitionTo(StartupState.COMPLETED)
        } catch (e: CancellationException) {
            markCancel()
            _startJobs.filter { it.isActive }.forEach { it.cancel() }
            throw e
        } catch (e: Throwable) {
            transitionTo(StartupState.ERROR)
            throw e
        } finally {
            _startJobs.clear()
            tracer.closeAsyncSection(span)
        }
    }

    internal fun areAllStartJobsDone(): Boolean = startJobs.none { it.isActive }

    fun cancel() {
        markCancel()
        supervisorJob.cancel()
    }

    companion object {
        const val TAG = "[CoroutinesEngine]"
        const val EXTENSION_NAME = "AppStartupCoroutinesEngine"
        private const val ACTIVE_ASYNC = "Startup::activeAsync"
        private const val PERMITS_WAITING = "Startup::permitsWaiting"
        private const val PERMITS_AVAILABLE = "Startup::permitsAvailable"
    }
}

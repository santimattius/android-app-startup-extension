package com.santimattius.android.sample.usecases

import com.santimattius.android.sample.AsyncTestInitializer
import io.github.santimattius.android.startup.awaitAllStartJobs
import io.github.santimattius.android.startup.isAllStartedJobsDone
import io.github.santimattius.android.startup.onAppStartupLaunched
import io.github.santimattius.android.startup.startupState
import kotlinx.coroutines.TimeoutCancellationException

/** Use case B: waiting for startup. Only meaningful during the 5 s window after process start. */
object WaitingCases {

    /** Runs [await] and reports how it ended; [done] is sampled afterwards. */
    suspend fun measure(
        label: String,
        done: () -> Boolean,
        state: () -> Any,
        await: suspend () -> Unit,
    ): List<String> {
        val before = "state before: ${state()}, jobs done: ${done()}"
        val started = System.nanoTime()
        val outcome = try {
            await()
            "returned normally"
        } catch (e: TimeoutCancellationException) {
            "TimeoutCancellationException"
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        return listOf(
            before,
            "$label: $outcome after $ms ms",
            "state after: ${state()}, isAllStartedJobsDone(): ${done()}",
        )
    }

    val all: List<DemoCase> = listOf(
        DemoCase(
            "timed", "awaitAllStartJobs(timeoutMs = 800)",
            "Throws TimeoutCancellationException while the 5 s async initializer is running. " +
                "Observed: the library also cancels the still-active startup jobs (the initializer " +
                "ends as CANCELLED) and startupState is left IN_PROGRESS.",
        ) { s ->
            measure("awaitAllStartJobs(800)", s::isAllStartedJobsDone, { s.startupState.value }) {
                s.awaitAllStartJobs(800)
            } + "AsyncTestInitializer metric: " +
                (s.lastMetricOf(AsyncTestInitializer::class.java)?.outcome() ?: "none yet")
        },
        DemoCase(
            "untimed", "awaitAllStartJobs()",
            "Suspends until every startup job finished. Right after a restart this takes ~5 s.",
        ) { s ->
            measure("awaitAllStartJobs()", s::isAllStartedJobsDone, { s.startupState.value }) {
                s.awaitAllStartJobs()
            }
        },
        DemoCase(
            "launched", "onAppStartupLaunched { }",
            "Awaits all jobs, then runs the block with the initializer.",
        ) { s ->
            var ran = false
            measure("onAppStartupLaunched", s::isAllStartedJobsDone, { s.startupState.value }) {
                s.onAppStartupLaunched { ran = true }
            } + "block executed: $ran"
        },
    )
}

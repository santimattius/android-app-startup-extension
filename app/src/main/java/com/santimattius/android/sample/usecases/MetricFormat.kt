package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.StartupMetric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withTimeoutOrNull

/** One-line outcome used as the card header. */
fun StartupMetric.outcome(): String = when {
    wasCancelled -> "CANCELLED"
    success -> "OK"
    else -> "FAILED"
}

/** Every [StartupMetric] field as a label/value row, in the order the library documents them. */
fun StartupMetric.rows(): List<Pair<String, String>> = listOf(
    "fqn" to initializerFqn.ifEmpty { initializerName },
    "kind" to if (isAsync) "async" else "sync (main thread)",
    "duration" to "$durationMs ms",
    "thread" to threadName.ifEmpty { "-" },
    "dispatcher" to dispatcherName.ifEmpty { "-" },
    "concurrentActiveCount" to concurrentActiveCount.toString(),
    "queueDelay" to "$queueDelayMs ms",
    "permitWait" to "$permitWaitMs ms",
    "start / end (uptime)" to "$startTimestampMs / $endTimestampMs",
    "processStartOffset" to "$processStartOffsetMs ms",
    "priority" to priority.name,
    "error" to (errorType?.let { "$it: $errorMessage" } ?: "-"),
)

/** Cumulative list of every metric emitted so far (a late subscriber starts with the replay). */
fun Flow<StartupMetric>.accumulate(): Flow<List<StartupMetric>> =
    runningFold(emptyList()) { acc, metric -> acc + metric }

/**
 * Subscribes now and returns what arrives within [windowMs]. Against a replaying SharedFlow this
 * is exactly the bounded replay a late collector receives.
 */
suspend fun <T> Flow<T>.collectWindow(windowMs: Long): List<T> {
    val received = mutableListOf<T>()
    withTimeoutOrNull(windowMs) { collect { received += it } }
    return received
}

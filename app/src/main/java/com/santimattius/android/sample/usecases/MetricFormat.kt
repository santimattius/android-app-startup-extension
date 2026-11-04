package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.StartupMetric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.runningFold

/** One-line outcome used as the card header. */
fun StartupMetric.outcome(): String =
    when {
        wasCancelled -> "CANCELLED"
        success -> "OK"
        else -> "FAILED"
    }

/** Every [StartupMetric] field as a label/value row, in the order the library documents them. */
fun StartupMetric.rows(): List<Pair<String, String>> =
    listOf(
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
        "error" to
            errorType
                ?.let { type ->
                    errorMessage?.let { message -> "$type: $message" } ?: type
                }.orEmpty()
                .ifEmpty { "-" },
    )

/** Cumulative list of every metric emitted so far (a late subscriber starts with the replay). */
fun Flow<StartupMetric>.accumulate(): Flow<List<StartupMetric>> = runningFold(emptyList()) { acc, metric -> acc + metric }

/**
 * Returns the bounded replay snapshot available to a late collector at the instant it attaches.
 * Values emitted after this call are deliberately excluded.
 */
fun <T> SharedFlow<T>.replaySnapshot(): List<T> = replayCache.toList()

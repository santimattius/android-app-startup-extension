package com.santimattius.android.sample.usecases

import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.StartupMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/** One runnable use case: [run] exercises the library and returns the lines to display. */
class DemoCase(
    val id: String,
    val title: String,
    val description: String,
    val run: suspend (AppStartupInitializer) -> List<String>,
)

/** Rejects overlapping UI-triggered cases and preserves structured coroutine cancellation. */
class DemoCaseRunner {
    private val activeCaseId = AtomicReference<String?>(null)
    private val _runningCaseId = MutableStateFlow<String?>(null)
    val runningCaseId: StateFlow<String?> = _runningCaseId.asStateFlow()

    /** Returns null when another case already owns the shared runner. */
    suspend fun run(
        case: DemoCase,
        startup: AppStartupInitializer,
    ): List<String>? {
        if (!activeCaseId.compareAndSet(null, case.id)) return null
        _runningCaseId.value = case.id
        return try {
            case.run(startup)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            listOf("threw ${error::class.simpleName}: ${error.message}")
        } finally {
            activeCaseId.set(null)
            _runningCaseId.value = null
        }
    }

    companion object {
        /** Shared across screen recreation so an in-flight case remains visible and exclusive. */
        val shared = DemoCaseRunner()
    }
}

/** Most recent metric emitted for [cls] (the bounded replay cache holds the latest ones). */
fun AppStartupInitializer.lastMetricOf(cls: Class<*>): StartupMetric? = metricsFlow.replayCache.lastOrNull { it.initializerFqn == cls.name }

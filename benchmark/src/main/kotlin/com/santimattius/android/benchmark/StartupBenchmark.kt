package com.santimattius.android.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start benchmark of the sample (`benchmark` build: minified, non-debuggable, profileable).
 *
 * Reports TTID/TTFD (`StartupTimingMetric`; TTFD needs the sample's `reportFullyDrawn`) and the
 * library's `Startup::` trace sections. Emulator numbers are not valid performance data: run on
 * a physical device, or pass `androidx.benchmark.suppressErrors=EMULATOR` only to check wiring.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() = startup(CompilationMode.None())

    @Test
    fun startupCompilationPartial() = startup(CompilationMode.Partial())

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()) + sectionMetrics,
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
    ) {
        pressHome()
        startActivityAndWait()
        // The sample's async initializer suspends for ASYNC_INIT_MS; keep the trace open until
        // its (async, process-track) slices end so the section metrics can see them.
        Thread.sleep(ASYNC_INIT_MS + SLACK_MS)
    }

    private companion object {
        const val PACKAGE = "com.santimattius.android.sample"
        const val ASYNC_INIT_MS = 5_000L
        const val SLACK_MS = 1_000L

        // `%` is a SQL LIKE wildcard. The sample has no initializer long enough to trigger the
        // 127-char truncation (D1), so there is no shortened-name case to assert on here.
        val sectionMetrics = listOf(
            TraceSectionMetric("Startup::%", TraceSectionMetric.Mode.Sum, label = "allSections"),
            TraceSectionMetric("Startup::%", TraceSectionMetric.Mode.Count, label = "sectionCount"),
            // sync slices
            TraceSectionMetric("Startup::create::$PACKAGE.SyncTestInitializer", label = "syncCreate"),
            // async (process-track) slices
            TraceSectionMetric("Startup::permitWait::$PACKAGE.AsyncTestInitializer", label = "asyncPermitWait"),
            TraceSectionMetric("Startup::create::$PACKAGE.AsyncTestInitializer", label = "asyncCreate"),
        )
    }
}

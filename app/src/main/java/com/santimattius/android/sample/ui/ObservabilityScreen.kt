package com.santimattius.android.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.santimattius.android.sample.usecases.accumulate
import com.santimattius.android.sample.usecases.outcome
import com.santimattius.android.sample.usecases.replaySnapshot
import com.santimattius.android.sample.usecases.rows
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.StartupMetric
import io.github.santimattius.android.startup.isAllStartedJobsDone
import io.github.santimattius.android.startup.startupState
import kotlinx.coroutines.delay

/** Use case A: startupState, metricsFlow, isAllStartedJobsDone and replay to a late collector. */
@Composable
fun ObservabilityScreen(padding: PaddingValues) {
    val context = LocalContext.current.applicationContext
    val startup = remember { AppStartupInitializer.getInstance(context) }
    val state by startup.startupState.collectAsStateWithLifecycle()
    val metrics by remember { startup.metricsFlow.accumulate() }
        .collectAsStateWithLifecycle(emptyList())
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val jobsDone by produceState(false, lifecycleState) {
        if (lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
            while (true) {
                value = startup.isAllStartedJobsDone()
                delay(250)
            }
        }
    }
    var replay by remember { mutableStateOf<List<StartupMetric>?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("startupState = $state", style = MaterialTheme.typography.titleMedium)
                    Text("isAllStartedJobsDone() = $jobsDone")
                    Text(
                        "State is IN_PROGRESS while the 5 s AsyncTestInitializer runs and " +
                            "COMPLETED once MainApplication's onAppStartupLaunched awaited it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Late collector (bounded replay)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A new collector receives the most recent metricsReplayCapacity metrics " +
                            "(256 by default) even though they were emitted before it subscribed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        replay = startup.metricsFlow.replaySnapshot()
                    }) { Text("Attach late collector") }
                    replay?.let { received ->
                        Text(
                            "Received ${received.size} replayed metric(s): " +
                                received.joinToString { it.initializerName },
                        )
                    }
                }
            }
        }
        item { Text("metricsFlow (${metrics.size})", style = MaterialTheme.typography.titleMedium) }
        // Append-only list: the index is a stable, collision-free key.
        itemsIndexed(metrics, key = { index, _ -> index }) { _, metric -> MetricCard(metric) }
    }
}

@Composable
fun MetricCard(metric: StartupMetric) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${metric.initializerName} - ${metric.outcome()}",
                style = MaterialTheme.typography.titleSmall,
            )
            metric.rows().forEach { (label, value) ->
                Row {
                    Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.labelSmall)
                    Text(
                        value,
                        Modifier.weight(0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

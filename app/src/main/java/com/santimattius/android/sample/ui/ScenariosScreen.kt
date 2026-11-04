package com.santimattius.android.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.santimattius.android.sample.scenario.ScenarioStore
import com.santimattius.android.sample.scenario.StartupScenario
import com.santimattius.android.sample.usecases.ProcessRestart
import com.santimattius.android.sample.usecases.accumulate
import com.santimattius.android.sample.usecases.collectWindow
import io.github.santimattius.android.startup.AppStartupInitializer
import kotlinx.coroutines.launch

/** Debug only: pick a scenario, restart, and read the evidence of what it changed. */
@Composable
fun ScenariosScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val startup = remember { AppStartupInitializer.getInstance(context.applicationContext) }
    var selected by remember { mutableStateOf(ScenarioStore.selected(context)) }
    val active = ScenarioStore.applied
    val metrics by remember { startup.metricsFlow.accumulate() }.collectAsStateWithLifecycle(emptyList())
    var replayed by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Active in this process: ${active.title}", style = MaterialTheme.typography.titleMedium)
                    Text(active.description, style = MaterialTheme.typography.bodySmall)
                    Text("Observe: ${active.observe}", style = MaterialTheme.typography.bodySmall)
                    if (selected != active) {
                        Text("Selected, applies after restart: ${selected.title}")
                    }
                    Button(onClick = { ProcessRestart.restart(context, Route.Scenarios) }) {
                        Text("Restart process")
                    }
                }
            }
        }
        items(StartupScenario.entries, key = { it.name }) { scenario ->
            Row(Modifier.fillMaxWidth()) {
                RadioButton(selected = scenario == selected, onClick = {
                    selected = scenario
                    ScenarioStore.select(context, scenario)
                })
                Text(scenario.title, Modifier.padding(top = 12.dp))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Evidence", style = MaterialTheme.typography.titleMedium)
                    Text("dispatcherSnapshots: ${startup.dispatcherSnapshots().values.joinToString {
                        "${it.label}(peak=${it.peakConcurrency})"
                    }.ifEmpty { "empty" }}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scope.launch { replayed = startup.metricsFlow.collectWindow(300).size } }) {
                        Text("Count replayed metrics")
                    }
                    replayed?.let { Text("Late collector received $it metric(s)") }
                }
            }
        }
        items(metrics.size, key = { it }) { i ->
            val m = metrics[i]
            Text(
                "${m.initializerName}: offset=${m.processStartOffsetMs} dur=${m.durationMs} " +
                    "permit=${m.permitWaitMs} q=${m.queueDelayMs} act=${m.concurrentActiveCount} " +
                    "${m.dispatcherName.ifEmpty { "sync" }} ${m.priority}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

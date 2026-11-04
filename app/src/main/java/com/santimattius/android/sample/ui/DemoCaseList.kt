package com.santimattius.android.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.santimattius.android.sample.usecases.DemoCase
import io.github.santimattius.android.startup.AppStartupInitializer
import kotlinx.coroutines.launch

/** Generic list: one card per [DemoCase] with a Run button and its last output. */
@Composable
fun DemoCaseList(
    padding: PaddingValues,
    cases: List<DemoCase>,
    header: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val startup = remember { AppStartupInitializer.getInstance(context) }
    // HashMap is Serializable, so the outputs survive rotation/process recreation of the activity.
    val outputs = rememberSaveable { mutableStateOf(HashMap<String, List<String>>()) }
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header?.let { item { it() } }
        items(cases, key = { it.id }) { case ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(case.title, style = MaterialTheme.typography.titleMedium)
                    Text(case.description, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        scope.launch {
                            val lines = runCatching { case.run(startup) }
                                .getOrElse { listOf("threw ${it::class.simpleName}: ${it.message}") }
                            outputs.value = HashMap(outputs.value).apply { put(case.id, lines) }
                        }
                    }) { Text("Run") }
                    outputs.value[case.id]?.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

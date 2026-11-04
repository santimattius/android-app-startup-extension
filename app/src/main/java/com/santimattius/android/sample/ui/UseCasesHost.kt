package com.santimattius.android.sample.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Hosts every screen except [Route.Home]; composed only after the user opens the use cases. */
@Composable
fun UseCasesHost(route: Route, onNavigate: (Route) -> Unit) {
    val parent = route.parent ?: Route.Home
    BackHandler { onNavigate(parent) }
    UseCaseScaffold(route.title, onBack = { onNavigate(parent) }) { padding ->
        when (route) {
            Route.Observability -> ObservabilityScreen(padding)
            else -> HubScreen(padding, onNavigate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UseCaseScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onBack) { Text("Back") } })
        },
        content = content,
    )
}

@Composable
private fun HubScreen(padding: PaddingValues, onNavigate: (Route) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        items(Route.useCases, key = { it.name }) { route ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(route.title, style = MaterialTheme.typography.titleMedium)
                    Text(route.summary, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onNavigate(route) }) { Text("Open") }
                }
            }
        }
    }
}

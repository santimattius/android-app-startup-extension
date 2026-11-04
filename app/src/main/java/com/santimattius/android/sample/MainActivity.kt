package com.santimattius.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.santimattius.android.sample.ui.Route
import com.santimattius.android.sample.ui.UseCasesHost
import com.santimattius.android.sample.ui.theme.SampleTheme
import io.github.santimattius.android.startup.AppStartupInitializer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SampleTheme {
                var route by rememberSaveable { mutableStateOf(Route.Home.name) }
                val current = Route.fromName(route)
                if (current == Route.Home) {
                    MainScreen(onOpenUseCases = { route = Route.Hub.name })
                } else {
                    UseCasesHost(current) { route = it.name }
                }
                // The sample's content is static, so first composition means "data ready".
                // ReportDrawn defers to the activity's FullyDrawnReporter, which calls
                // reportFullyDrawn() once (enables TTFD in Macrobenchmark / Perfetto).
                ReportDrawn()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        /* TODO: manual initialization
        val initializer = AppStartupInitializer.getInstance(this@MainActivity)
        //Sync
        initializer.doInitialize<Unit>(SyncTestInitializer::class.java)

        lifecycleScope.launch {
            initializer.doInitialize<Unit>(AsyncTestInitializer::class.java)
        }
        */
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onOpenUseCases: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Startup Sample") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Greeting("Hello App Startup!")
                Button(onClick = onOpenUseCases) { Text("Use cases") }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showSystemUi = true)
@Composable
fun GreetingPreview() {
    SampleTheme {
        MainScreen(onOpenUseCases = {})
    }
}
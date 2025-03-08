package com.santimattius.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.santimattius.android.sample.ui.theme.SampleTheme
import io.github.santimattius.android.startup.AppStartupInitializer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SampleTheme {
                MainScreen()
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
private fun MainScreen() {
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
            Greeting("Hello App Startup!")
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
        MainScreen()
    }
}
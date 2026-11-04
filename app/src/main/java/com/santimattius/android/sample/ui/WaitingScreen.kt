package com.santimattius.android.sample.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.santimattius.android.sample.usecases.ProcessRestart
import com.santimattius.android.sample.usecases.WaitingCases

/** Use case B. The cases need running startup jobs, so a restart shortcut is provided. */
@Composable
fun WaitingScreen(padding: PaddingValues) {
    val context = LocalContext.current
    DemoCaseList(padding, WaitingCases.all) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "The 5 s AsyncTestInitializer only runs right after process start. Once it " +
                        "finished, every wait returns immediately. Restart to land here again with " +
                        "the startup jobs still running, then tap a case within 5 s."
                )
                Button(onClick = { ProcessRestart.restart(context, Route.Waiting) }) {
                    Text("Restart process and open this screen")
                }
            }
        }
    }
}

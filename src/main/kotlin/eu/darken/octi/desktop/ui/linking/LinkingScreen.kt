package eu.darken.octi.desktop.ui.linking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.ui.LocalAppGraph

/**
 * First-run linking screen. User pastes a share code from their phone, presses Link, sees one
 * of three outcomes: success (auto-navigates to Dashboard via [LinkingViewModel.handleResult]),
 * actionable error message, or progress spinner.
 *
 * No code-scanning affordance — the desktop has no camera + QR pipeline. Paste is the only
 * input method (matches the plan).
 */
@Composable
fun LinkingScreen() {
    val graph = LocalAppGraph.current
    val vm = remember(graph) { LinkingViewModel(graph) }
    val state by vm.state.collectAsState()

    var rawCode by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 32.dp, vertical = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Link this device to Octi",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "On your phone, open Octi → Settings → Devices → Add device. Copy the link " +
                    "code and paste it below. Codes expire after 60 minutes.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 600.dp),
            )

            OutlinedTextField(
                value = rawCode,
                onValueChange = {
                    rawCode = it
                    vm.dismissError()
                },
                label = { Text("Link code") },
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                enabled = state !is LinkingUiState.Working,
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
            )

            when (val s = state) {
                LinkingUiState.Idle -> Unit
                LinkingUiState.Working -> CircularProgressIndicator()
                is LinkingUiState.Error -> Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(max = 600.dp),
                )
            }

            Button(
                onClick = { vm.submit(rawCode) },
                enabled = rawCode.isNotBlank() && state !is LinkingUiState.Working,
            ) {
                Text("Link this device")
            }
        }
    }
}

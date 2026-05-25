package eu.darken.octi.desktop.ui.linking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.nav.Screen

/**
 * First-run linking screen. Two paths via the segmented control at the top:
 *
 *  - **Existing account** — paste a link code from another device. Server is dictated by the
 *    code's `serverAddress`.
 *  - **New account** — register a brand-new account against either the production server (blank
 *    input) or a user-provided URL/IP+port. The same URL value backs `Settings.createAccountServerUrl`.
 *
 * The Settings icon in the app bar is reachable pre-link so the user can configure other
 * options (theme, label) before either path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkingScreen() {
    val graph = LocalAppGraph.current
    val vm = remember(graph) { LinkingViewModel(graph) }
    val state by vm.state.collectAsState()
    val mode by vm.mode.collectAsState()
    val settingsSnapshot by graph.settings.flow.collectAsState()

    var rawCode by remember { mutableStateOf("") }
    var serverUrlInput by remember(settingsSnapshot.createAccountServerUrl) {
        mutableStateOf(settingsSnapshot.createAccountServerUrl.orEmpty())
    }

    val isWorking = state is LinkingUiState.Working

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link this device to Octi") },
                actions = {
                    IconButton(
                        onClick = { graph.navigator.navigateTo(Screen.Settings) },
                        enabled = !isWorking,
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Open settings")
                    }
                },
            )
        },
    ) { contentPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PaddingValues(horizontal = 32.dp, vertical = 24.dp)),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == LinkingMode.ExistingAccount,
                        onClick = { vm.setMode(LinkingMode.ExistingAccount) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        enabled = !isWorking,
                    ) { Text("Existing account") }
                    SegmentedButton(
                        selected = mode == LinkingMode.NewAccount,
                        onClick = { vm.setMode(LinkingMode.NewAccount) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        enabled = !isWorking,
                    ) { Text("New account") }
                }

                when (mode) {
                    LinkingMode.ExistingAccount -> ExistingAccountPane(
                        rawCode = rawCode,
                        onRawCodeChange = {
                            rawCode = it
                            vm.dismissError()
                        },
                        onSubmit = { vm.submit(rawCode) },
                        isWorking = isWorking,
                    )
                    LinkingMode.NewAccount -> NewAccountPane(
                        serverUrl = serverUrlInput,
                        onServerUrlChange = {
                            serverUrlInput = it
                            vm.dismissError()
                        },
                        onCreate = { vm.createAccount(serverUrlInput) },
                        isWorking = isWorking,
                    )
                }

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
            }
        }
    }
}

@Composable
internal fun ExistingAccountPane(
    rawCode: String,
    onRawCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isWorking: Boolean,
) {
    Column(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "On your phone, open Octi → Settings → Devices → Add device. Copy the link " +
                "code and paste it below. Codes expire after 60 minutes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = rawCode,
            onValueChange = onRawCodeChange,
            label = { Text("Link code") },
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            enabled = !isWorking,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp),
        )
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = onSubmit,
                enabled = rawCode.isNotBlank() && !isWorking,
            ) { Text("Link this device") }
        }
    }
}

@Composable
internal fun NewAccountPane(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onCreate: () -> Unit,
    isWorking: Boolean,
) {
    Column(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Create a fresh Octi account on the production server, or point this device at a " +
                "different one. Leave the server field blank to use the default.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL (optional)") },
            placeholder = { Text(OctiServer.Official.PROD.address.address) },
            singleLine = true,
            enabled = !isWorking,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = onCreate,
                enabled = !isWorking,
            ) { Text("Create account") }
        }
    }
}

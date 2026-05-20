package eu.darken.octi.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.linking.UnlinkResult
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import eu.darken.octi.desktop.platform.PlatformDetector
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.storage.ThemeMode
import eu.darken.octi.desktop.ui.LocalAppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val graph = LocalAppGraph.current
    val settings by graph.settings.flow.collectAsState()
    val primaryConnector by graph.primaryConnector.collectAsState()
    val linkedCredentials = primaryConnector?.credentials

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { graph.navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    .padding(PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DeviceLabelSetting(
                    current = settings.deviceLabel.orEmpty(),
                    onChange = { value ->
                        graph.settings.update { it.copy(deviceLabel = value.ifBlank { null }) }
                    },
                )
                ServerSetting(
                    createAccountServerUrl = settings.createAccountServerUrl.orEmpty(),
                    linkedServer = linkedCredentials?.serverAdress,
                    onSave = { value ->
                        // UI commits only valid (or blank → null) values. The TextField's local
                        // state stays unconstrained between saves so the user can correct typos.
                        graph.settings.update { it.copy(createAccountServerUrl = value) }
                    },
                )
                ThemeModeSetting(
                    current = settings.themeMode,
                    onChange = { value ->
                        graph.settings.update { it.copy(themeMode = value) }
                    },
                )
                ClipboardSetting(
                    enabled = settings.clipboardAutoSync,
                    onToggle = { value ->
                        graph.settings.update { it.copy(clipboardAutoSync = value) }
                    },
                )
                EncryptionModeRow(
                    keysetType = linkedCredentials?.encryptionKeyset?.type,
                )
                AccountSection(graph = graph)
                AboutSection()
            }
        }
    }
}

@Composable
private fun ServerSetting(
    createAccountServerUrl: String,
    linkedServer: OctiServer.Address?,
    onSave: (String?) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Server", style = MaterialTheme.typography.titleSmall)
            if (linkedServer != null) {
                Text(
                    "Connected to ${linkedServer.address}. Unlink this device to change the server.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "URL of the Octi server the \"Create new account\" flow will use. Leave blank " +
                        "to use the production server (${OctiServer.Official.PROD.address.address}).",
                    style = MaterialTheme.typography.bodySmall,
                )
                ServerUrlEditor(initial = createAccountServerUrl, onSave = onSave)
            }
        }
    }
}

@Composable
private fun ServerUrlEditor(initial: String, onSave: (String?) -> Unit) {
    var localValue by remember(initial) { mutableStateOf(initial) }
    val trimmed = localValue.trim()
    val parseResult = remember(trimmed) {
        if (trimmed.isEmpty()) null else OctiServer.Address.tryParse(trimmed)
    }
    val hasUserEdit = localValue != initial
    val errorMessage = parseResult?.exceptionOrNull()?.message
    val canSave = hasUserEdit && parseResult?.isFailure != true

    OutlinedTextField(
        value = localValue,
        onValueChange = { localValue = it },
        singleLine = true,
        placeholder = { Text(OctiServer.Official.PROD.address.address) },
        isError = errorMessage != null,
        supportingText = errorMessage?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = { onSave(trimmed.takeIf { it.isNotEmpty() }) },
            enabled = canSave,
        ) { Text("Save") }
    }
}

@Composable
private fun DeviceLabelSetting(current: String, onChange: (String) -> Unit) {
    var localValue by remember(current) { mutableStateOf(current) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Device label", style = MaterialTheme.typography.titleSmall)
            Text(
                "Shown to your other devices. Leave blank to use the system hostname.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = localValue,
                onValueChange = { localValue = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onChange(localValue) },
                    enabled = localValue != current,
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun ThemeModeSetting(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Follows the system theme by default.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { expanded = true }) { Text(current.name) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemeMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.name) },
                        onClick = {
                            onChange(mode)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardSetting(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Sync clipboard", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Off by default. When on, plain-text clipboard contents (up to 32 KB) are " +
                        "shared with your other devices.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun EncryptionModeRow(keysetType: String?) {
    val isGcmSiv = keysetType == "AES256_GCM_SIV"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Encryption", style = MaterialTheme.typography.titleSmall)
            Text(
                text = keysetType ?: "(not linked)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isGcmSiv) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            if (keysetType != null && !isGcmSiv) {
                Text(
                    "This account is on the legacy cipher (AES256_SIV). File sharing is " +
                        "disabled — it needs AES256_GCM_SIV. The keyset is set when the " +
                        "account is first created on a phone; re-linking adopts the same one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("About", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Octi Desktop", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Version ${DeviceMetadataProvider.APP_VERSION}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Links", style = MaterialTheme.typography.labelMedium)
                LinkRow("This app", "https://github.com/d4rken-org/octi-desktop", uriHandler)
                LinkRow("Octi (Android)", "https://github.com/d4rken-org/octi", uriHandler)
                LinkRow("Octi Server", "https://github.com/d4rken-org/octi-server", uriHandler)
                LinkRow("Octi Web", "https://github.com/d4rken-org/octi-web", uriHandler)
                LinkRow("Discord", "https://discord.gg/s7V4C6zuVy", uriHandler)
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Data paths", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Paths the app reads + writes on this machine. Tap the copy button to put a " +
                        "path on your clipboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PathRow("Config", PlatformDetector.configDir().toString(), clipboard)
                PathRow("Data", PlatformDetector.dataDir().toString(), clipboard)
            }
        }
    }
}

@Composable
private fun LinkRow(
    label: String,
    url: String,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = { uriHandler.openUri(url) }) { Text("Open") }
    }
}

@Composable
private fun PathRow(
    label: String,
    path: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(path)) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "Copy $label path",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountSection(graph: AppGraph) {
    var working by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val primaryConnector by graph.primaryConnector.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Account", style = MaterialTheme.typography.titleSmall)
            Text(
                "Unlinking calls the server to remove this device, then clears local credentials. " +
                    "If the server can't be reached, local state is kept so you can retry.",
                style = MaterialTheme.typography.bodySmall,
            )
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val targetConnectorId = primaryConnector?.identifier
            Button(
                enabled = !working && targetConnectorId != null,
                onClick = {
                    val connectorId = targetConnectorId ?: return@Button
                    errorMessage = null
                    working = true
                    // Launch on the app scope so navigation away mid-unlink doesn't cancel the
                    // server DELETE. UI mutations are hopped onto Swing explicitly — Compose
                    // state is safest to write from the AWT EDT.
                    graph.appScope.launch {
                        val result = try {
                            graph.unlink(connectorId)
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (e: Throwable) {
                            UnlinkResult.NetworkError(e)
                        }
                        withContext(Dispatchers.Swing) {
                            when (result) {
                                UnlinkResult.Success,
                                UnlinkResult.NotLinked -> Unit
                                is UnlinkResult.NetworkError -> errorMessage = "Couldn't unlink: " +
                                    (result.cause.message ?: result.cause.javaClass.simpleName) +
                                    ". Local credentials kept; try again when online."
                                is UnlinkResult.LocalCleanupFailed -> errorMessage = "The server " +
                                    "removed this device, but clearing local credentials failed: " +
                                    (result.cause.message ?: result.cause.javaClass.simpleName) +
                                    ". Restart the app and try unlinking again — until that " +
                                    "succeeds the app will reconnect to a deleted device."
                            }
                            working = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(if (working) "Unlinking…" else "Unlink this device") }
        }
    }
}

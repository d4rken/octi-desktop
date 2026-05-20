package eu.darken.octi.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.linking.UnlinkResult
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import eu.darken.octi.desktop.platform.PlatformDetector
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.storage.ThemeMode
import eu.darken.octi.desktop.sync.DeviceListRepo
import eu.darken.octi.desktop.sync.OctiServerWebSocketClient
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.nav.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val graph = LocalAppGraph.current
    val settings by graph.settings.flow.collectAsState()

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
                ConnectorsSection(graph = graph)
                AboutSection()
            }
        }
    }
}

@Composable
private fun ServerSetting(
    createAccountServerUrl: String,
    onSave: (String?) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Server prefill", style = MaterialTheme.typography.titleSmall)
            Text(
                "URL the next \"New account\" flow will prefill. Leave blank to default to the " +
                    "production server (${OctiServer.Official.PROD.address.address}). Linked " +
                    "connectors keep using their own server regardless of this value.",
                style = MaterialTheme.typography.bodySmall,
            )
            ServerUrlEditor(initial = createAccountServerUrl, onSave = onSave)
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
private fun ConnectorsSection(graph: AppGraph) {
    val connectors by graph.activeConnectors.collectAsState()
    val settings by graph.settings.flow.collectAsState()
    val wsStates by graph.webSocketClient.statesByConnector.collectAsState()
    val loadStates by graph.deviceListRepo.loadStateByConnector.collectAsState()
    val mergedDevices by graph.deviceListRepo.mergedDevices.collectAsState()
    val lastWritesByConnector by graph.metaWriter.lastWriteSuccessAtByConnector.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Linked accounts",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (connectors.isEmpty()) {
            // Reachable when the user unlinked every connector but stayed on Settings —
            // navigator snaps to Linking on activeConnectors becoming empty, so this is rare
            // but worth covering rather than rendering an empty column.
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No accounts linked. Use the Linking screen to add one.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            connectors.forEach { connector ->
                val id = connector.identifier
                val paused = settings.connectors[id.idString]?.paused == true
                val deviceCount = mergedDevices.count { it.sources.contains(id) }
                ConnectorCard(
                    graph = graph,
                    connector = connector,
                    paused = paused,
                    wsState = wsStates[id],
                    loadState = loadStates[id],
                    deviceCount = deviceCount,
                    lastWriteSuccessAt = lastWritesByConnector[id],
                )
            }
        }
        OutlinedButton(
            onClick = { graph.navigator.navigateTo(Screen.Linking) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("  Add another account")
        }
    }
}

@Composable
private fun ConnectorCard(
    graph: AppGraph,
    connector: OctiServerConnector,
    paused: Boolean,
    wsState: OctiServerWebSocketClient.ConnectionState?,
    loadState: DeviceListRepo.LoadState?,
    deviceCount: Int,
    lastWriteSuccessAt: kotlin.time.Instant?,
) {
    var working by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val id = connector.identifier
    val keysetType = connector.credentials.encryptionKeyset.type
    val isGcmSiv = keysetType == "AES256_GCM_SIV"

    // Paused cards keep all controls reachable but visually fade so the user can tell at a
    // glance which connector is silenced. Alpha only — the buttons remain enabled.
    Box(modifier = Modifier.alpha(if (paused) 0.55f else 1f)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (paused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            connector.accountLabel ?: connector.credentials.serverAdress.address,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Account ${connector.credentials.accountId.id.take(8)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = !paused,
                        onCheckedChange = { wantActive -> graph.setPaused(id, paused = !wantActive) },
                    )
                }
                ConnectorStatusLine(
                    wsState = wsState,
                    loadState = loadState,
                    deviceCount = deviceCount,
                    paused = paused,
                    lastWriteSuccessAt = lastWriteSuccessAt,
                )
                Text(
                    text = "Encryption: $keysetType",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isGcmSiv) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
                if (!isGcmSiv) {
                    Text(
                        "Legacy keyset — file sharing is disabled for this account. The keyset is " +
                            "set when the account is first created on a phone; re-linking adopts the same one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        enabled = !working,
                        onClick = {
                            errorMessage = null
                            working = true
                            // App-scope launch — navigating away during unlink mustn't cancel
                            // the server DELETE. UI mutations are hopped onto Swing.
                            graph.appScope.launch {
                                val result = try {
                                    graph.unlink(id)
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (e: Throwable) {
                                    UnlinkResult.NetworkError(e)
                                }
                                withContext(Dispatchers.Swing) {
                                    when (result) {
                                        UnlinkResult.Success,
                                        UnlinkResult.NotLinked -> Unit
                                        is UnlinkResult.NetworkError -> errorMessage =
                                            "Couldn't unlink: " +
                                                (result.cause.message ?: result.cause.javaClass.simpleName) +
                                                ". Local credentials kept; try again when online."
                                        is UnlinkResult.LocalCleanupFailed -> errorMessage =
                                            "The server removed this device, but clearing local " +
                                                "credentials failed: " +
                                                (result.cause.message ?: result.cause.javaClass.simpleName) +
                                                ". Restart the app and try unlinking again — until " +
                                                "that succeeds the app will reconnect to a deleted device."
                                    }
                                    working = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text(if (working) "Unlinking…" else "Unlink") }
                }
            }
        }
    }
}

@Composable
private fun ConnectorStatusLine(
    wsState: OctiServerWebSocketClient.ConnectionState?,
    loadState: DeviceListRepo.LoadState?,
    deviceCount: Int,
    paused: Boolean,
    lastWriteSuccessAt: kotlin.time.Instant?,
) {
    val wsLabel = when {
        paused -> "Paused"
        wsState == null -> "Idle"
        wsState is OctiServerWebSocketClient.ConnectionState.Idle -> "Idle"
        wsState is OctiServerWebSocketClient.ConnectionState.Connecting -> "Connecting…"
        wsState is OctiServerWebSocketClient.ConnectionState.Connected -> "Connected"
        wsState is OctiServerWebSocketClient.ConnectionState.Reconnecting -> "Reconnecting (attempt ${wsState.attempt})"
        wsState is OctiServerWebSocketClient.ConnectionState.PollingFallback -> "Polling fallback"
        else -> "Idle"
    }
    val deviceLabel = when {
        paused -> "$deviceCount device(s) — last known"
        loadState is DeviceListRepo.LoadState.Loading -> "Loading devices…"
        loadState is DeviceListRepo.LoadState.Error -> "Device list error: ${loadState.message}"
        else -> "$deviceCount device(s)"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "$wsLabel · $deviceLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Last sync · ${lastWriteSuccessAt?.formatAsLocalTime() ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Format an [Instant] as a short local-time string for the Settings card. Uses the system
 * default zone — the Settings card is only ever read by the user on the host where the app
 * runs, so zone-aware formatting is correct. Falls back to ISO if the platform's date-time
 * formatter throws for any reason.
 */
private fun kotlin.time.Instant.formatAsLocalTime(): String = try {
    val javaInstant = java.time.Instant.ofEpochMilli(this.toEpochMilliseconds())
    val localDateTime = javaInstant
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(localDateTime)
} catch (_: Throwable) {
    this.toString()
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


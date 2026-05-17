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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.storage.ThemeMode
import eu.darken.octi.desktop.ui.LocalAppGraph

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
                    keysetType = graph.credentialsStore.load()?.encryptionKeyset?.type,
                )
                AccountSection(onUnlink = { graph.unlink() })
            }
        }
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
private fun AccountSection(onUnlink: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Account", style = MaterialTheme.typography.titleSmall)
            Text(
                "Unlinking removes this device from your Octi account locally. To also remove " +
                    "it from the server, delete it from another device's settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onUnlink,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Unlink this device") }
        }
    }
}

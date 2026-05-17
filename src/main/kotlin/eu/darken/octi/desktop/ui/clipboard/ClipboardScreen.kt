package eu.darken.octi.desktop.ui.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.modules.clipboard.ClipboardEntry
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.dashboard.Presence

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen() {
    val graph = LocalAppGraph.current
    val settings by graph.settings.flow.collectAsState()
    val entry by graph.clipboardSync.currentEntry.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clipboard") },
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
                    .padding(PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AutoSyncToggle(
                    enabled = settings.clipboardAutoSync,
                    onToggle = { value ->
                        graph.settings.update { it.copy(clipboardAutoSync = value) }
                    },
                )
                IncomingClipboard(entry = entry)
            }
        }
    }
}

@Composable
private fun AutoSyncToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Sync clipboard with peers", style = MaterialTheme.typography.titleSmall)
                Text(
                    "When enabled, your local clipboard is sent to and received from your other devices. " +
                        "Plain text only, up to 32 KB. Off by default.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun IncomingClipboard(entry: ClipboardEntry?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Last received from peers", style = MaterialTheme.typography.titleSmall)
            if (entry == null) {
                Text(
                    "No clipboard updates received yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val source = entry.sourceDeviceLabel ?: entry.sourceDeviceId.take(8)
                Text(
                    "From $source • ${Presence.describe(entry.receivedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

package eu.darken.octi.desktop.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.sync.DeviceListRepo
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.nav.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val graph = LocalAppGraph.current
    val devices by graph.deviceListRepo.devices.collectAsState()
    val loadState by graph.deviceListRepo.loadState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                actions = {
                    if (loadState is DeviceListRepo.LoadState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { graph.deviceListRepo.kick() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = { graph.navigator.navigateTo(Screen.Clipboard) }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Clipboard")
                    }
                    IconButton(onClick = { graph.navigator.navigateTo(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { contentPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            if (devices.isEmpty()) {
                EmptyState(loadState = loadState)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            isSelf = device.id == graph.deviceId.id,
                            onClick = {
                                graph.navigator.navigateTo(Screen.DeviceDetail(device.id))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(loadState: DeviceListRepo.LoadState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (loadState) {
                is DeviceListRepo.LoadState.Loading,
                DeviceListRepo.LoadState.Initial -> CircularProgressIndicator()
                is DeviceListRepo.LoadState.Error -> Text(
                    text = "Couldn't reach the server: ${loadState.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text("No devices yet")
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DevicesResponse.Device,
    isSelf: Boolean,
    onClick: () -> Unit,
) {
    val online = Presence.isOnline(device.lastSeen)
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = iconFor(device.platform),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = device.label ?: device.id.take(8),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val subtitle = listOfNotNull(
                        device.platform?.takeIf { it.isNotBlank() },
                        device.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
                    ).joinToString(" • ")
                    if (subtitle.isNotEmpty()) {
                        Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(online = online)
                Text(
                    text = buildString {
                        append(if (online) "Online" else "Offline")
                        append(" • ")
                        append(Presence.describe(device.lastSeen))
                        if (isSelf) append(" • this device")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
    )
}

private fun iconFor(platform: String?): ImageVector {
    val p = platform?.lowercase().orEmpty()
    return when {
        p.contains("desktop") -> Icons.Filled.Computer
        p.contains("tablet") -> Icons.Filled.Tablet
        p.contains("phone") || p.contains("android") -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.DeviceUnknown
    }
}


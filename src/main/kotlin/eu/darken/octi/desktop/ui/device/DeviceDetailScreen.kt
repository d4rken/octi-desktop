package eu.darken.octi.desktop.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.power.PowerInfo
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.ui.LocalAppGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(deviceId: String) {
    val graph = LocalAppGraph.current
    val targetDeviceId = remember(deviceId) { DeviceId(deviceId) }
    val vm = remember(graph, targetDeviceId) { DeviceDetailViewModel(graph, targetDeviceId) }

    // Try to find a Dashboard-side device entry so we can show a friendly title.
    val devices by graph.deviceListRepo.devices.collectAsState()
    val device = remember(devices, deviceId) { devices.firstOrNull { it.id == deviceId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.label ?: deviceId.take(8)) },
                navigationIcon = {
                    IconButton(onClick = { graph.navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        graph.navigator.navigateTo(
                            eu.darken.octi.desktop.ui.nav.Screen.Files(deviceId = deviceId),
                        )
                    }) {
                        Icon(Icons.Filled.Folder, contentDescription = "Files")
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { contentPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PowerSection(state = vm.powerState.collectAsState().value)
                // Meta / Wifi / Connectivity / Apps / Files sections land in later phases. The
                // section pattern stays identical — load via ModuleReader, render a Card. The
                // Power section here is the end-to-end wire-path proof for the MVP slice.
            }
        }
    }
}

@Composable
private fun PowerSection(state: DeviceDetailViewModel.ModuleState<PowerInfo>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.BatteryFull, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Power", style = MaterialTheme.typography.titleMedium)
            }
            when (state) {
                DeviceDetailViewModel.ModuleState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                DeviceDetailViewModel.ModuleState.NotFound -> Text(
                    "This device hasn't shared power data yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is DeviceDetailViewModel.ModuleState.Error -> Text(
                    "Couldn't read power data: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is DeviceDetailViewModel.ModuleState.Ok -> PowerContent(state.value)
            }
        }
    }
}

@Composable
private fun PowerContent(power: PowerInfo) {
    val percent = power.battery.percent.coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${(percent * 100).toInt()}%",
            style = MaterialTheme.typography.displaySmall,
        )
        LinearProgressIndicator(
            progress = { percent },
            modifier = Modifier.fillMaxWidth(),
        )
        val statusText = buildString {
            append(power.status.name)
            if (power.isCharging) append(" (charging)")
            power.battery.temp?.let { append(" • ${"%.1f".format(it)}°") }
        }
        Text(statusText, style = MaterialTheme.typography.bodyMedium)
        val chargeIo = power.chargeIO
        val chargeNow = chargeIo.currentNow
        if (chargeNow != null) {
            Text(
                text = "Current: ${chargeNow / 1000} mA • Speed: ${chargeIo.speed}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

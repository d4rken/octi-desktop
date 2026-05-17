package eu.darken.octi.desktop.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.AwtWindow
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.dashboard.Presence
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(deviceId: String) {
    val graph = LocalAppGraph.current
    val targetDeviceId = remember(deviceId) { DeviceId(deviceId) }
    val vm = remember(graph, targetDeviceId) { FilesViewModel(graph, targetDeviceId) }
    val state by vm.state.collectAsState()

    // Find a friendly name for the screen title.
    val devices by graph.deviceListRepo.devices.collectAsState()
    val device = remember(devices, deviceId) { devices.firstOrNull { it.id == deviceId } }
    val title = when {
        vm.isSelf -> "Your files"
        device?.label != null -> "${device.label} • Files"
        else -> "${deviceId.take(8)} • Files"
    }

    val showPicker = remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showPicker.value) {
        FilePickerDialog(
            onChoice = { chosen ->
                showPicker.value = false
                if (chosen != null) vm.share(chosen)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { graph.navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            if (vm.isSelf && !state.legacyKeysetBlocksUpload) {
                ExtendedFloatingActionButton(
                    onClick = { showPicker.value = true },
                    icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                    text = { Text("Share file…") },
                )
            }
        },
    ) { contentPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.legacyKeysetBlocksUpload && vm.isSelf) {
                    LegacyKeysetWarning()
                }
                when {
                    state.loading && state.files.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    state.error != null && state.files.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Couldn't load files: ${state.error}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.files.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val emptyText = if (vm.isSelf) "You haven't shared any files yet."
                        else "No files shared by this device."
                        Text(emptyText)
                    }
                    else -> FileList(state = state, vm = vm)
                }
            }
        }
    }
}

@Composable
private fun LegacyKeysetWarning() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "File sharing requires the newer encryption mode. Open Octi on your phone " +
                "and re-link this device to enable uploads.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FileList(state: FilesUiState, vm: FilesViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(items = state.files, key = { it.blobKey }) { file ->
            FileRow(
                file = file,
                action = state.actions[file.blobKey],
                isSelf = vm.isSelf,
                onDownload = { vm.download(file, vm.defaultDownloadFor(file)) },
                onDelete = { vm.requestDeletion(file) },
            )
        }
    }
}

@Composable
private fun FileRow(
    file: FileShareInfo.SharedFile,
    action: Action?,
    isSelf: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
                Text(
                    "${humanSize(file.size)} • ${file.mimeType} • shared ${Presence.describe(file.sharedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (action is Action.Failed) {
                    Text(action.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (action) {
                Action.Uploading, Action.Downloading, Action.Deleting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                else -> {
                    if (!isSelf) {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Filled.Download, contentDescription = "Download")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = if (isSelf) "Stop sharing" else "Request deletion",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePickerDialog(onChoice: (java.nio.file.Path?) -> Unit) {
    // AWT FileDialog has the best per-OS native chooser. Compose Multiplatform doesn't ship a
    // file-picker out of the box yet, so we drop down to AwtWindow.
    AwtWindow(
        create = {
            object : FileDialog(null as Frame?, "Share a file with your other devices", LOAD) {
                override fun setVisible(b: Boolean) {
                    super.setVisible(b)
                    if (b) {
                        val name = file
                        val dir = directory
                        val path = if (name != null && dir != null) Paths.get(dir, name) else null
                        onChoice(path)
                    }
                }
            }
        },
        dispose = FileDialog::dispose,
    )
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var size = bytes.toDouble() / 1024
    var unitIdx = 0
    while (size >= 1024 && unitIdx < units.size - 1) {
        size /= 1024
        unitIdx++
    }
    return "%.1f %s".format(size, units[unitIdx])
}

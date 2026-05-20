package eu.darken.octi.desktop.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.storage.SettingsData
import eu.darken.octi.desktop.sync.DeviceListRepo
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.dashboard.details.ModuleDetailSheet
import eu.darken.octi.desktop.ui.dashboard.editor.TileEditorCard
import eu.darken.octi.desktop.ui.dashboard.editor.TileEditorState
import eu.darken.octi.desktop.ui.dashboard.layout.TileLayoutConfig
import eu.darken.octi.desktop.ui.dashboard.layout.normalize
import eu.darken.octi.desktop.ui.dashboard.layout.toRows
import eu.darken.octi.desktop.ui.nav.Screen

private val TAG = logTag("UI", "Dashboard")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val graph = LocalAppGraph.current
    val devices by graph.deviceListRepo.devices.collectAsState()
    val loadState by graph.deviceListRepo.loadState.collectAsState()
    val settings by graph.settings.flow.collectAsState()

    // Tracks which tile sheet is open across the whole dashboard. Single source so opening one
    // tile closes any other one — matches Android's modal-stack behavior.
    var openSheet by remember { mutableStateOf<Pair<String, ModuleSpec<*>>?>(null) }

    // Which device card is currently in edit mode, if any. Held at screen scope so scrolling
    // the outer LazyVerticalGrid doesn't lose in-flight edits if the edited item disposes.
    var editingDeviceId by remember { mutableStateOf<String?>(null) }
    // Keyed ONLY on editingDeviceId — settings writes during an active edit must NOT recreate
    // the editor, or they'd silently wipe unsaved drag work. The initial snapshot is captured
    // when editingDeviceId transitions from null → id; subsequent settings changes are ignored.
    val editor: TileEditorState? = remember(editingDeviceId) {
        editingDeviceId?.let { id ->
            TileEditorState(settings.effectiveLayoutFor(id).normalize(ModuleSpec.allModuleIds))
        }
    }

    // Prune stale per-device layout entries — but only after a successful load. The aggregate
    // [DeviceListRepo.loadState] returns `Ok` only when EVERY configured connector reports Ok,
    // so this gate is multi-connector-safe: a single offline connector won't cause us to wipe
    // layouts for devices that only happen to be visible via that connector. Read the stale
    // set from the collected `settings` snapshot (not graph.settings.data, which would race a
    // second read-lock acquisition).
    LaunchedEffect(devices, loadState) {
        if (loadState !is DeviceListRepo.LoadState.Ok) return@LaunchedEffect
        val liveIds = devices.map { it.id }.toSet()
        val stale = settings.tileLayouts.keys - liveIds
        if (stale.isNotEmpty()) {
            log(TAG) { "Pruning ${stale.size} stale tile-layout entries: $stale" }
            graph.settings.update { data ->
                data.copy(tileLayouts = data.tileLayouts.filterKeys { it in liveIds })
            }
        }
    }

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
                    columns = GridCells.Adaptive(minSize = 360.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = devices, key = { it.id }) { device ->
                        DeviceCardOrEditor(
                            device = device,
                            isSelf = device.id == graph.deviceId.id,
                            editingDeviceId = editingDeviceId,
                            editor = editor,
                            settings = settings,
                            onTileClick = { spec -> openSheet = device.id to spec },
                            onStartEdit = {
                                log(TAG) { "Edit started for device=${device.id.take(8)}" }
                                editingDeviceId = device.id
                            },
                            onCancelEdit = {
                                log(TAG) { "Edit cancelled for device=${device.id.take(8)}" }
                                editingDeviceId = null
                            },
                            onSave = { state ->
                                val cfg = state.toConfig().normalize(ModuleSpec.allModuleIds)
                                log(TAG) { "Saving tile layout for device=${device.id.take(8)}: $cfg" }
                                graph.settings.update { it.copy(tileLayouts = it.tileLayouts + (device.id to cfg)) }
                                editingDeviceId = null
                            },
                            onReset = {
                                log(TAG) { "Resetting tile layout for device=${device.id.take(8)}" }
                                graph.settings.update { it.copy(tileLayouts = it.tileLayouts - device.id) }
                                editingDeviceId = null
                            },
                            onSaveAsDefault = { state ->
                                val cfg = state.toConfig().normalize(ModuleSpec.allModuleIds)
                                log(TAG) { "Saving tile layout as default (and clearing all per-device overrides): $cfg" }
                                // Single atomic update: set the default and wipe all per-device overrides
                                // so every existing device snaps to the new default — matches Android.
                                graph.settings.update {
                                    it.copy(defaultTileLayout = cfg, tileLayouts = emptyMap())
                                }
                                editingDeviceId = null
                            },
                        )
                    }
                }
            }
        }
    }

    openSheet?.let { (deviceId, spec) ->
        ModuleDetailSheet(
            deviceId = deviceId,
            spec = spec,
            onDismiss = { openSheet = null },
        )
    }
}

@Composable
private fun DeviceCardOrEditor(
    device: DevicesResponse.Device,
    isSelf: Boolean,
    editingDeviceId: String?,
    editor: TileEditorState?,
    settings: SettingsData,
    onTileClick: (ModuleSpec<*>) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: (TileEditorState) -> Unit,
    onReset: () -> Unit,
    onSaveAsDefault: (TileEditorState) -> Unit,
) {
    if (device.id == editingDeviceId && editor != null) {
        TileEditorCard(
            editor = editor,
            deviceLabel = device.label ?: device.id.take(8),
            deviceId = remember(device.id) { DeviceId(device.id) },
            onSave = { onSave(editor) },
            onCancel = onCancelEdit,
            onReset = onReset,
            onSaveAsDefault = { onSaveAsDefault(editor) },
        )
    } else {
        DashboardDeviceCard(
            device = device,
            isSelf = isSelf,
            settings = settings,
            onTileClick = onTileClick,
            onStartEdit = onStartEdit,
        )
    }
}

@Composable
private fun EmptyState(loadState: DeviceListRepo.LoadState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (loadState) {
                DeviceListRepo.LoadState.Loading -> CircularProgressIndicator()
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
private fun DashboardDeviceCard(
    device: DevicesResponse.Device,
    isSelf: Boolean,
    settings: SettingsData,
    onTileClick: (ModuleSpec<*>) -> Unit,
    onStartEdit: () -> Unit,
) {
    val online = Presence.isOnline(device.lastSeen)
    val deviceId = remember(device.id) { DeviceId(device.id) }
    val layout = remember(settings, device.id) {
        settings.effectiveLayoutFor(device.id).normalize(ModuleSpec.allModuleIds)
    }
    val rows = remember(layout) { layout.toRows(ModuleSpec.allModuleIds) }

    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceHeader(device = device, isSelf = isSelf, online = online, onStartEdit = onStartEdit)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ModuleTileGrid(rows = rows, deviceId = deviceId, onTileClick = onTileClick)
        }
    }
}

@Composable
private fun DeviceHeader(
    device: DevicesResponse.Device,
    isSelf: Boolean,
    online: Boolean,
    onStartEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = iconFor(device.platform),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.label ?: device.id.take(8),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(online = online)
                Text(
                    text = buildString {
                        append(if (online) "Online" else "Offline")
                        append(" • ")
                        append(Presence.describe(device.lastSeen))
                        if (isSelf) append(" • this device")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OverflowMenu(onEditTiles = onStartEdit)
    }
}

@Composable
private fun OverflowMenu(onEditTiles: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Card menu")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Edit tiles") },
                onClick = {
                    expanded = false
                    onEditTiles()
                },
            )
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
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
        p == "web" || p.contains("browser") -> Icons.Filled.Language
        p.contains("phone") || p.contains("android") -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.DeviceUnknown
    }
}

/**
 * Looks up the effective tile layout for [deviceId] — the per-device override if present,
 * otherwise the global default. Mirrors Android `DashboardConfig.effectiveLayout`.
 */
private fun SettingsData.effectiveLayoutFor(deviceId: String): TileLayoutConfig =
    tileLayouts[deviceId] ?: defaultTileLayout

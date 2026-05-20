package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.sync.ConnectorId

/**
 * A device entry as observed across all configured sync connectors. [device] is the merged
 * representative — newest `lastSeen` wins for the metadata fields — and [sources] is the set
 * of connector ids that reported a row for this `device.id`.
 *
 * Today every entry has `sources.size == 1` (single OctiServer connector) but the type is
 * shaped multi-source from the start so debug surfaces and the Android-parity merge layer
 * can be written against the shape that GDrive support will need. Mirrors Android's
 * `SyncRead.Device` post-`latestData()` shape conceptually, though the desktop merge happens
 * at device level (Android also merges per-module — that comes when desktop ports the
 * SyncRead/SyncWrite abstraction).
 */
data class MergedDevice(
    val device: DevicesResponse.Device,
    val sources: Set<ConnectorId>,
)

/**
 * Merge a per-connector device list into a flat [MergedDevice] list. Group by `device.id`,
 * sources = union of connectors that reported it, representative = `maxByOrNull { lastSeen }`
 * (falls back to first when all `lastSeen` are null), capabilities = union but **only when at
 * least one source actually reported a non-null set** (the null-vs-empty distinction is
 * load-bearing per the device-capabilities authority rules — see `rules/device-capabilities.md`).
 */
internal fun mergeDeviceLists(
    perConnector: Map<ConnectorId, List<DevicesResponse.Device>>,
): List<MergedDevice> {
    if (perConnector.isEmpty()) return emptyList()
    val groups: MutableMap<String, MutableList<Pair<ConnectorId, DevicesResponse.Device>>> = mutableMapOf()
    perConnector.forEach { (connectorId, devices) ->
        devices.forEach { device ->
            groups.getOrPut(device.id) { mutableListOf() } += connectorId to device
        }
    }
    return groups.values.map { rows ->
        val sources = rows.map { it.first }.toSet()
        val representative = rows.maxByOrNull { it.second.lastSeen?.toEpochMilliseconds() ?: Long.MIN_VALUE }!!.second
        // Capability merge: union across sources, but preserve null-when-no-source-reported.
        val anyReported = rows.any { it.second.capabilities != null }
        val mergedCaps: Set<String>? = if (anyReported) {
            rows.flatMap { it.second.capabilities ?: emptySet() }.toSet()
        } else null
        MergedDevice(
            device = representative.copy(capabilities = mergedCaps),
            sources = sources,
        )
    }
}

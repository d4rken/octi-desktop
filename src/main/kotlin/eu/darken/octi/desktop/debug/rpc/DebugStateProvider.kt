package eu.darken.octi.desktop.debug.rpc

import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.sync.MergedDevice
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Source of `/dev/state` JSON snapshots. Extracted as an interface so the Ktor route tests can
 * substitute a static payload without constructing a real [AppGraph].
 */
interface DebugStateSource {
    fun snapshot(): JsonObject
}

/**
 * Production [DebugStateSource]. Reads `.value` from each observable graph flow rather than
 * collecting, so the response is fast and never blocks waiting for an event to arrive.
 *
 * Payload shape (multi-connector ready — array sizes are 0-or-1 today):
 *
 * ```json
 * {
 *   "version": "0.X.Y",
 *   "deviceId": "<uuid>",
 *   "screen": "dashboard",
 *   "connectors": [
 *     {
 *       "id": "kserver-host-acct",
 *       "type": "octiserver",
 *       "webSocketState": "Connected",
 *       "deviceListLoadState": "Ok"
 *     }
 *   ],
 *   "deviceCount": 2,
 *   "knownDevices": [
 *     { "deviceId": "...", "label": "...", "platform": "...", "lastSeen": "...",
 *       "capabilities": [...], "sources": ["kserver-host-acct"] }
 *   ],
 *   "lastMetaWriteSuccessAt": "...",
 *   "lastWsEventAt": "..."
 * }
 * ```
 *
 * The old `activeClientPresent`, top-level `webSocketState`, and top-level `deviceListLoadState`
 * are gone — callers iterate `connectors[]` for per-connector detail and union/check across
 * sources for "is anything active" predicates.
 */
class DebugStateProvider(private val graph: AppGraph) : DebugStateSource {

    override fun snapshot(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(DeviceMetadataProvider.APP_VERSION))
        put("deviceId", JsonPrimitive(graph.deviceId.id))
        put("screen", JsonPrimitive(graph.navigator.current.value.routeName()))
        put("connectors", connectorsArray())
        val merged = graph.deviceListRepo.mergedDevices.value
        put("deviceCount", JsonPrimitive(merged.size))
        put("knownDevices", knownDevicesArray(merged))
        put("lastMetaWriteSuccessAt", graph.metaWriter.lastWriteSuccessAt.value?.toString().toJson())
        put("lastWsEventAt", graph.syncEventBus.lastEventAt.value?.toString().toJson())
    }

    private fun connectorsArray() = buildJsonArray {
        val wsStates = graph.webSocketClient.statesByConnector.value
        val loadStates = graph.deviceListRepo.loadStateByConnector.value
        val pausedIds = graph.settings.data.connectors
            .filterValues { it.paused }
            .map { it.value.connectorId }
            .toSet()
        val lastWritesByConnector = graph.metaWriter.lastWriteSuccessAtByConnector.value
        graph.activeConnectors.value.forEach { connector ->
            val id = connector.identifier
            add(buildJsonObject {
                put("id", JsonPrimitive(id.idString))
                put("type", JsonPrimitive(id.type.typeId))
                put("webSocketState", JsonPrimitive(wsStates[id]?.let { it::class.simpleName } ?: "Idle"))
                put("deviceListLoadState", JsonPrimitive(loadStates[id]?.let { it::class.simpleName } ?: "Loading"))
                put("paused", JsonPrimitive(id in pausedIds))
                put("lastMetaWriteSuccessAt", lastWritesByConnector[id]?.toString().toJson())
            })
        }
    }

    private fun knownDevicesArray(merged: List<MergedDevice>) = buildJsonArray {
        merged.forEach { m ->
            add(buildJsonObject {
                put("deviceId", JsonPrimitive(m.device.id))
                put("label", m.device.label.toJson())
                put("platform", m.device.platform.toJson())
                put("lastSeen", m.device.lastSeen?.toString().toJson())
                // Preserve the null-vs-empty distinction — null = peer hasn't reported,
                // empty array = peer explicitly reports no capabilities. The Capability
                // authority semantics depend on this difference.
                put(
                    "capabilities",
                    m.device.capabilities?.let { caps ->
                        buildJsonArray { caps.sorted().forEach { add(JsonPrimitive(it)) } }
                    } ?: JsonNull,
                )
                put(
                    "sources",
                    buildJsonArray {
                        m.sources.map { it.idString }.sorted().forEach { add(JsonPrimitive(it)) }
                    },
                )
            })
        }
    }

    private fun String?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun eu.darken.octi.desktop.ui.nav.Screen.routeName(): String = when (this) {
        eu.darken.octi.desktop.ui.nav.Screen.Linking -> "linking"
        eu.darken.octi.desktop.ui.nav.Screen.Dashboard -> "dashboard"
        eu.darken.octi.desktop.ui.nav.Screen.Clipboard -> "clipboard"
        eu.darken.octi.desktop.ui.nav.Screen.Settings -> "settings"
        is eu.darken.octi.desktop.ui.nav.Screen.Files -> "files:${this.deviceId}"
    }

    @Suppress("unused")
    private val keepImportsHonest: DevicesResponse.Device? = null
}

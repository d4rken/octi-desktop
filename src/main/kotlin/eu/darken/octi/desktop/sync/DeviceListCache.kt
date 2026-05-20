package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.files.AtomicWrites
import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.platform.PlatformDetector
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path

private val TAG = logTag("Sync", "DeviceListCache")

/**
 * Persists the most-recent device list per connector so the Dashboard has something to show
 * before the first successful sync round. Writes are atomic (see [AtomicWrites]); reads
 * tolerate a missing or corrupted file by returning an empty map rather than throwing — a
 * stale cache is more useful than a startup crash.
 *
 * The on-disk envelope is `{schemaVersion, perConnector: Map<idString, List<Device>>}`. The
 * map key is [ConnectorId.idString] (opaque); structured ids are derived by the
 * [DeviceListRepo] when needed. The envelope is multi-connector ready from day one — today
 * there's at most one entry, but a future GDrive connector adds entries without an on-disk
 * schema change.
 *
 * Discovery filtering: at load time the cache filters out per-connector entries whose
 * idString is no longer in [eu.darken.octi.desktop.storage.SettingsData.connectors]. Prevents a
 * stale cache surviving an unlink/relink cycle from resurrecting ghost devices.
 */
class DeviceListCache(
    private val file: Path = PlatformDetector.dataDir().resolve("device-list.json"),
) {

    fun load(knownConnectorIds: Set<String>): Map<String, List<DevicesResponse.Device>> {
        if (!Files.exists(file)) return emptyMap()
        return try {
            val bytes = Files.readAllBytes(file)
            val envelope = Serialization.json.decodeFromString(
                Envelope.serializer(),
                bytes.toString(Charsets.UTF_8),
            )
            // Drop entries for connectors no longer configured — see class kdoc.
            envelope.perConnector.filterKeys { it in knownConnectorIds }
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN, e) { "Failed to read $file; discarding cache" }
            emptyMap()
        }
    }

    /** Replace the whole cached map. Used by [DeviceListRepo] after merging in fresh data. */
    fun saveAll(perConnector: Map<String, List<DevicesResponse.Device>>) {
        try {
            val envelope = Envelope(perConnector = perConnector)
            val json = Serialization.json.encodeToString(Envelope.serializer(), envelope)
            AtomicWrites.writeText(file, json)
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN, e) { "Failed to persist device list cache" }
        }
    }

    fun clear() {
        try {
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN, e) { "Failed to delete device-list cache" }
        }
    }

    @Serializable
    private data class Envelope(
        val schemaVersion: Int = 1,
        val perConnector: Map<String, List<DevicesResponse.Device>>,
    )

    @Suppress("unused")
    private val deviceListSerializer = ListSerializer(DevicesResponse.Device.serializer())
}

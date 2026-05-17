package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.files.AtomicWrites
import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.platform.PlatformDetector
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.serialization.Serialization
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path

private val TAG = logTag("Sync", "DeviceListCache")

/**
 * Persists the most-recent device list on disk so the Dashboard has something to show before
 * the first successful sync round. Writes are atomic (see [AtomicWrites]); reads tolerate a
 * missing or corrupted file by returning `null` rather than throwing — a stale cache is more
 * useful than a startup crash.
 */
class DeviceListCache(
    private val file: Path = PlatformDetector.dataDir().resolve("device-list.json"),
) {

    private val serializer = ListSerializer(DevicesResponse.Device.serializer())

    fun load(): List<DevicesResponse.Device>? {
        if (!Files.exists(file)) return null
        return try {
            val bytes = Files.readAllBytes(file)
            Serialization.json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN, e) { "Failed to read $file; discarding cache" }
            null
        }
    }

    fun save(devices: List<DevicesResponse.Device>) {
        try {
            val json = Serialization.json.encodeToString(serializer, devices)
            AtomicWrites.writeText(file, json)
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN, e) { "Failed to persist device list" }
        }
    }

    fun clear() {
        try {
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN, e) { "Failed to delete device-list cache" }
        }
    }
}

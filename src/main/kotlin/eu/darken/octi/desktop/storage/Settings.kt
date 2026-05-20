package eu.darken.octi.desktop.storage

import eu.darken.octi.desktop.common.files.AtomicWrites
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.platform.PlatformDetector
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.ui.dashboard.layout.TileLayoutConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val TAG = logTag("Settings")

/**
 * On-disk JSON settings store. Non-secret app preferences only — credentials live in [Keystore].
 *
 * Atomic writes via [AtomicWrites]. Cross-process [FileLock] held only across read/write
 * operations to keep multiple desktop instances from corrupting each other's settings.
 */
class Settings private constructor(
    private val file: Path,
    private val json: Json,
    initial: SettingsData,
) {

    private val cacheLock = ReentrantReadWriteLock()
    private val _flow = MutableStateFlow(initial)
    private var cache: SettingsData = initial

    /** Reactive view for Compose / coroutines. Always reflects the latest persisted state. */
    val flow: StateFlow<SettingsData> = _flow.asStateFlow()

    val data: SettingsData
        get() = cacheLock.read { cache }

    fun update(transform: (SettingsData) -> SettingsData) {
        val updated = cacheLock.write {
            val next = transform(cache)
            if (next.schemaVersion != cache.schemaVersion) {
                error("Cannot bump schemaVersion via update(); use a migration.")
            }
            cache = next
            next
        }
        persist(updated)
        _flow.value = updated
    }

    private fun persist(data: SettingsData) = withFileLock {
        val bytes = json.encodeToString(SettingsData.serializer(), data).toByteArray(Charsets.UTF_8)
        AtomicWrites.writeBytes(file, bytes)
    }

    private fun <T> withFileLock(block: () -> T): T {
        Files.createDirectories(file.parent)
        val lockPath = file.resolveSibling("${file.fileName}.lock")
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock: FileLock = channel.lock()
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }

    companion object {

        private const val SCHEMA_VERSION = 2

        fun load(
            json: Json = defaultJson,
            file: Path = PlatformDetector.configDir().resolve("settings.json"),
        ): Settings {
            Files.createDirectories(file.parent)
            val initial = if (Files.exists(file)) {
                val bytes = Files.readAllBytes(file)
                try {
                    migrate(json.decodeFromString(SettingsData.serializer(), bytes.toString(Charsets.UTF_8)))
                } catch (e: Exception) {
                    log(TAG, eu.darken.octi.desktop.common.log.Logging.Priority.WARN, e) {
                        "settings.json unreadable; falling back to defaults"
                    }
                    SettingsData()
                }
            } else {
                SettingsData()
            }
            // Mint a stable deviceId on first launch. Persisted immediately so a crash before
            // the first user-driven update still preserves the id.
            val finalized = if (initial.deviceId == null) {
                initial.copy(deviceId = java.util.UUID.randomUUID().toString())
            } else {
                initial
            }
            return Settings(file, json, finalized).also { it.persist(finalized) }
        }

        private fun migrate(raw: SettingsData): SettingsData {
            // Add migrations here as schemaVersion bumps.
            return raw.copy(schemaVersion = SCHEMA_VERSION)
        }

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class SettingsData(
    val schemaVersion: Int = 2,
    /**
     * Persistent UUID identifying this desktop install to the Octi server. Minted on first
     * launch (see [Settings.load]) and never rotated — re-linking against a different account
     * reuses the same deviceId so the server consolidates history under one peer entry. Null
     * here means "not yet minted"; the load path replaces null with a fresh UUID.
     */
    val deviceId: String? = null,
    val deviceLabel: String? = null,
    /**
     * User-configured server URL used by the "Create new account" flow. Null or blank means the
     * production server is used. Values are pre-validated by `OctiServer.Address.tryParse` before
     * being persisted — bad URLs never reach this field. After a successful create or link the
     * server lives in the per-connector credentials and this field is just the UI prefill for
     * the next create-account attempt.
     */
    val createAccountServerUrl: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val syncIntervalSeconds: Int = 300,
    /** Clipboard auto-sync is opt-in for privacy reasons. See plan for rationale. */
    val clipboardAutoSync: Boolean = false,
    /**
     * Layout applied to any device that doesn't have its own entry in [tileLayouts]. Mutating
     * this via "Save as default" in the editor also wipes [tileLayouts] so all devices snap to
     * the new shape — matches Android `GeneralSettings.setDefaultTileLayout`.
     */
    val defaultTileLayout: TileLayoutConfig = TileLayoutConfig(),
    /**
     * Per-device tile-layout overrides keyed by `DeviceId.id`. Empty by default. Pruned in the
     * dashboard whenever a device leaves the peer list (gated on a successful load so a
     * transient empty `devices` emission doesn't wipe every saved entry). The deviceId is
     * stable across connectors (Android merges by deviceId too) so this map is shared.
     */
    val tileLayouts: Map<String, TileLayoutConfig> = emptyMap(),
    /**
     * Configured sync connectors. Keys are [ConnectorId.idString] — opaque keys that are
     * comparable but not parsed; the structured [ConnectorId] lives inside [ConnectorConfig].
     * One entry per linked account. Today only OctiServer entries exist; a future GDrive
     * connector adds entries with `type = GDRIVE` without a schema migration.
     *
     * This map is the discovery index for [eu.darken.octi.desktop.linking.CredentialsStore] —
     * the keystore has no list-by-prefix across backends, so "which connectors exist" lives
     * here and the keystore is value storage keyed by [ConnectorId.idString].
     */
    val connectors: Map<String, ConnectorConfig> = emptyMap(),
)

/**
 * Per-connector configuration persisted in [SettingsData.connectors]. The structured
 * [connectorId] is stored alongside the map key (which is [ConnectorId.idString]) so consumers
 * never have to parse the idString — its `-` separator collides with hyphens inside hostnames
 * and UUIDs. Future per-connector knobs (e.g. polling intervals, mute flags) land here.
 *
 * [paused] gates ALL sync work for this connector (device list polling, websocket, meta /
 * clipboard writes, file share refresh). Mirrors Android's `SyncSettings.pausedConnectorIds`.
 * Default false; a pause toggle in Settings flips it. While paused, the connector still appears
 * in the Settings list (so the user can re-enable or unlink) but contributes no traffic.
 */
@Serializable
data class ConnectorConfig(
    val connectorId: ConnectorId,
    val paused: Boolean = false,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

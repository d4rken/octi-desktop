package eu.darken.octi.desktop.modules.meta

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import java.lang.ProcessHandle
import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

private val TAG = logTag("Module", "Meta", "Writer")

/**
 * Periodically writes this desktop's [MetaInfo] to `/v1/module/eu.darken.octi.module.core.meta`
 * on every linked, non-paused connector so peers on each server see us in the device list.
 *
 * Schema follows app-main PR #306: `deviceType=DESKTOP`, generic `osType`/`osVersionName`, all
 * Android-only fields left null. Android peers without #306 see the payload as malformed and
 * skip the meta tile for this device — accepted transitional cost while #306 rolls out.
 *
 * Cadence: write to every non-paused connector immediately on appear/relink (the [kickFlow]
 * trigger), then every [WRITE_INTERVAL]. Per-connector dedupe via
 * [lastWrittenPayloadByConnector] — if a connector already has the latest bytes we skip the
 * write to save rate-limit budget. A new connector arriving wakes the loop early so peers see
 * us within seconds of the link, not up to [WRITE_INTERVAL] later.
 *
 * Failures are per-connector independent: one connector throwing doesn't block writes to the
 * others. The aggregate [lastWriteSuccessAt] is "newest across all connectors" for the
 * dashboard meta tile (which renders our own device's info regardless of which connector(s)
 * we successfully reached).
 */
class MetaWriter(private val graph: AppGraph) {

    /**
     * Per-connector cache of the last successfully written plaintext payload. The fan-out loop
     * reads its entry per connector to skip no-op writes. Pruned on activeConnectors changes
     * (unlink) so a dead ConnectorId can't keep an entry alive forever.
     */
    private val lastWrittenPayloadByConnector: MutableMap<ConnectorId, ByteArray> = mutableMapOf()

    private val _lastWriteSuccessAtByConnector = MutableStateFlow<Map<ConnectorId, Instant>>(emptyMap())

    /**
     * Per-connector "when did we last successfully PUT meta on this connector?". Settings UI
     * + debug RPC consume this to surface freshness independently per connector. Missing key =
     * never successfully written (boot, all attempts failed so far).
     */
    val lastWriteSuccessAtByConnector: StateFlow<Map<ConnectorId, Instant>> =
        _lastWriteSuccessAtByConnector.asStateFlow()

    /**
     * Aggregate "newest meta write across any connector" — keeps the debug RPC top-level field
     * and the dashboard back-compat callers happy. Null until the first successful write
     * anywhere.
     */
    val lastWriteSuccessAt: StateFlow<Instant?> = _lastWriteSuccessAtByConnector
        .map { perConnector -> perConnector.values.maxOrNull() }
        .let { mapped ->
            val state = MutableStateFlow<Instant?>(null)
            mapped.onEach { state.value = it }.launchIn(graph.appScope)
            state.asStateFlow()
        }

    private val _lastWrittenInfo = MutableStateFlow<MetaInfo?>(null)

    /**
     * Snapshot of the [MetaInfo] we most recently *successfully* pushed. Same content goes to
     * every connector so this stays a single value rather than per-connector — null until the
     * first write anywhere succeeds. Used as the local source for the self-device Meta tile on
     * the Dashboard (WS suppresses self-events so the server-driven invalidation path would
     * never refresh our own tile).
     */
    val lastWrittenInfo: StateFlow<MetaInfo?> = _lastWrittenInfo.asStateFlow()

    /**
     * Wake signal for the write loop. New connector appearing, or pause toggling off, kicks
     * this so the meta write doesn't have to wait the full [WRITE_INTERVAL].
     */
    private val kickFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun start() {
        // Wake the loop whenever the running-connector set changes. The set changes for any of:
        // first link, additional link, pause toggle, unlink. The loop self-skips connectors
        // whose latest payload it already wrote (lastWrittenPayloadByConnector hit).
        graph.runningConnectors
            .onEach { kickFlow.tryEmit(Unit) }
            .launchIn(graph.appScope)

        // Prune the dedupe cache when a connector leaves activeConnectors entirely (unlink).
        // Paused connectors stay in activeConnectors so their cache survives a pause/resume
        // round-trip.
        graph.activeConnectors
            .onEach { connectors ->
                val activeIds = connectors.map { it.identifier }.toSet()
                lastWrittenPayloadByConnector.keys.retainAll(activeIds)
                _lastWriteSuccessAtByConnector.value =
                    _lastWriteSuccessAtByConnector.value.filterKeys { it in activeIds }
            }
            .launchIn(graph.appScope)

        graph.appScope.launch {
            while (true) {
                val targets = graph.runningConnectors.value
                if (targets.isNotEmpty()) {
                    writeOnce(targets)
                }
                withTimeoutOrNull(WRITE_INTERVAL.inWholeMilliseconds) { kickFlow.first() }
            }
        }
    }

    private suspend fun writeOnce(targets: List<OctiServerConnector>) {
        val info = buildMetaInfo()
        val plaintext = Serialization.json.encodeToString(MetaInfo.serializer(), info)
            .toByteArray(Charsets.UTF_8)
        // AAD is keyed on OUR deviceId + module — same for every connector.
        val aad = "${graph.deviceId.id}:${ModuleIds.META.id}".toByteArray(Charsets.UTF_8)

        for (connector in targets) {
            val connectorId = connector.identifier
            val lastWritten = lastWrittenPayloadByConnector[connectorId]
            if (plaintext.contentEquals(lastWritten)) {
                log(TAG, DEBUG) { "Meta payload unchanged for ${connectorId.logLabel}; skipping" }
                continue
            }
            try {
                // Encrypt per connector — each has its own keyset (different accounts).
                val crypto = PayloadEncryption(keySet = connector.credentials.encryptionKeyset)
                val ciphertext = crypto.encrypt(plaintext.toByteString().toGzip(), aad).toByteArray()
                connector.client.writeModule(ModuleIds.META, ciphertext)
                lastWrittenPayloadByConnector[connectorId] = plaintext
                val now = Clock.System.now()
                _lastWriteSuccessAtByConnector.value =
                    _lastWriteSuccessAtByConnector.value + (connectorId to now)
                _lastWrittenInfo.value = info
                log(TAG, DEBUG) {
                    "Meta payload written to ${connectorId.logLabel} " +
                        "(${plaintext.size}B plaintext, ${ciphertext.size}B ciphertext)"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                // One connector failing must not block the others. Next tick retries the
                // failed connector (no per-connector exponential backoff in v1; if a connector
                // is permanently broken the user can pause it from Settings).
                log(TAG, WARN, e) { "Meta write to ${connectorId.logLabel} failed; will retry on next tick" }
            }
        }
    }

    private fun buildMetaInfo(): MetaInfo = MetaInfo(
        deviceLabel = graph.settings.data.deviceLabel,
        deviceId = DeviceId(graph.deviceId.id),
        octiVersionName = DeviceMetadataProvider.APP_VERSION,
        octiGitSha = OCTI_GIT_SHA_PLACEHOLDER,
        deviceManufacturer = (System.getProperty("java.vendor")?.takeIf { it.isNotBlank() }
            ?: "JVM Desktop"),
        deviceName = hostnameOrUnknown(),
        deviceType = MetaInfo.DeviceType.DESKTOP,
        deviceBootedAt = processStartInstant(),
        osType = System.getProperty("os.name"),
        osVersionName = System.getProperty("os.version"),
    )

    private fun processStartInstant(): Instant {
        return try {
            val started = ProcessHandle.current().info().startInstant().orElse(null)
            started?.toKotlinInstant() ?: Clock.System.now()
        } catch (_: Exception) {
            Clock.System.now()
        }
    }

    private fun hostnameOrUnknown(): String = try {
        InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() } ?: "octi-desktop"
    } catch (_: Exception) {
        "octi-desktop"
    }

    companion object {
        private val WRITE_INTERVAL = 5.minutes

        // The Android source generates this from git via CommitHashValueSource at build time.
        // Desktop doesn't have that wiring yet — slot in a placeholder; replace when the
        // packaging phase (H) adds a similar BuildInfo generator.
        private const val OCTI_GIT_SHA_PLACEHOLDER = "desktop-dev"
    }
}

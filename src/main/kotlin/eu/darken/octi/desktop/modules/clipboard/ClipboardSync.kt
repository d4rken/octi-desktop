package eu.darken.octi.desktop.modules.clipboard

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.sync.ModuleReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val TAG = logTag("Module", "Clipboard", "Sync")

/**
 * Bidirectional clipboard sync. Default OFF — the user opts in via [Settings] because clipboard
 * contents are sensitive (passwords from a manager often land there).
 *
 * Outbound (local → server): polls the AWT clipboard once per [POLL_INTERVAL]. Plain string
 * flavour only; payloads > [ClipboardInfo.MAX_BYTES] are skipped silently rather than truncated
 * (silent truncation would be surprising — a partial password is worse than no sync).
 *
 * Inbound (server → local): subscribes to [eu.darken.octi.desktop.sync.SyncEventBus] for
 * `clipboard` module changes, fetches and decrypts the payload via [ModuleReader], updates the
 * displayed entry, and (if auto-sync is on) writes it to the local clipboard.
 *
 * Echo suppression: every push and every apply records a SHA-256 hash. A subsequent local
 * change matching either hash is treated as our own echo and ignored. Combined with the
 * WebSocket's `sourceDeviceId` filter, this prevents the desktop from re-broadcasting a
 * payload it just received.
 */
class ClipboardSync(private val graph: AppGraph) {

    private val _currentEntry = MutableStateFlow<ClipboardEntry?>(null)
    val currentEntry: StateFlow<ClipboardEntry?> = _currentEntry.asStateFlow()

    private val _lastPushedInfo = MutableStateFlow<ClipboardInfo?>(null)

    /**
     * Snapshot of the [ClipboardInfo] we most recently pushed to the server. Drives the
     * self-device Clipboard tile on the Dashboard — WS suppresses self-events so the server-
     * driven invalidation path would never refresh our own tile. Null until the user opts into
     * auto-sync and a clipboard push succeeds.
     */
    val lastPushedInfo: StateFlow<ClipboardInfo?> = _lastPushedInfo.asStateFlow()

    /** True if the toolkit is reachable on this host (headless servers will see false). */
    private val toolkitAvailable: Boolean by lazy {
        try {
            Toolkit.getDefaultToolkit()
            true
        } catch (_: HeadlessException) {
            log(TAG, INFO) { "AWT headless — clipboard sync disabled on this host" }
            false
        } catch (e: Throwable) {
            log(TAG, WARN, e) { "AWT toolkit init failed; clipboard sync disabled" }
            false
        }
    }

    /**
     * Per-connector "what hash we last pushed to that connector" cache. Today only the primary
     * connector receives writes, so this map has at most one entry; PR-4's fan-out reads its
     * entry per connector. [bootSnapshotHash] captures the at-boot clipboard so a fresh link
     * doesn't immediately push pre-existing user data — applied uniformly to every connector's
     * cache on first contact.
     */
    private val lastPushedHashByConnector: MutableMap<ConnectorId, ByteString> = mutableMapOf()

    /**
     * Clipboard hash captured at start(), used as the "pre-link content" baseline so we don't
     * push existing user data the moment a connector is linked. Each per-connector cache
     * starts at this value on first push attempt for that connector.
     */
    private var bootSnapshotHash: ByteString? = null

    private var lastAppliedHash: ByteString? = null

    fun start() {
        if (!toolkitAvailable) return

        // On boot, snapshot the current clipboard hash so we don't immediately push existing
        // user data (the user opted in to *sync*, not "upload everything sitting in my
        // clipboard right now").
        readLocalClipboardOrNull()?.let { (_, hash) -> bootSnapshotHash = hash }

        // Outbound poll loop.
        graph.appScope.launch {
            while (true) {
                if (graph.settings.data.clipboardAutoSync && graph.primaryConnector.value != null) {
                    runCatching { tryPushLocalClipboard() }
                        .onFailure { log(TAG, WARN, it) { "Outbound clipboard push failed" } }
                }
                delay(POLL_INTERVAL.inWholeMilliseconds)
            }
        }

        // Inbound: react to ModuleChanged for the clipboard module. Self-events are already
        // filtered by the WebSocket client; we still don't need to guard against own deviceId.
        graph.syncEventBus.events
            .filter { syncEvent ->
                val ev = syncEvent.event
                ev is EventPayload.Event.ModuleChanged && ev.moduleId == ModuleIds.CLIPBOARD.id
            }
            .onEach { syncEvent ->
                runCatching { tryPullPeerClipboard(syncEvent.event as EventPayload.Event.ModuleChanged) }
                    .onFailure { log(TAG, WARN, it) { "Inbound clipboard pull failed" } }
            }
            .launchIn(graph.appScope)
    }

    private suspend fun tryPushLocalClipboard() {
        val (text, hash) = readLocalClipboardOrNull() ?: return
        if (text.length > ClipboardInfo.MAX_BYTES) return // approx UTF-8 size bound; precise check below
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > ClipboardInfo.MAX_BYTES) return

        val connector = graph.primaryConnector.value ?: return
        val connectorId = connector.identifier

        // Echo suppression: don't push something we just pushed to THIS connector, don't push
        // something a peer sent us (already applied locally), and don't push pre-existing
        // clipboard content the user had before linking.
        val lastPushedForThisConnector = lastPushedHashByConnector[connectorId] ?: bootSnapshotHash
        if (hash == lastPushedForThisConnector || hash == lastAppliedHash) return

        val client = connector.client
        val crypto = PayloadEncryption(keySet = connector.credentials.encryptionKeyset)
        val info = ClipboardInfo(type = ClipboardInfo.Type.SIMPLE_TEXT, data = bytes.toByteString())
        val plaintext = Serialization.json.encodeToString(ClipboardInfo.serializer(), info)
            .toByteArray(Charsets.UTF_8)
        // Android wire format: gzip then encrypt with AAD = "${deviceId}:${moduleId}".
        val aad = "${graph.deviceId.id}:${ModuleIds.CLIPBOARD.id}".toByteArray(Charsets.UTF_8)
        val ciphertext = crypto.encrypt(plaintext.toByteString().toGzip(), aad).toByteArray()
        client.writeModule(ModuleIds.CLIPBOARD, ciphertext)
        lastPushedHashByConnector[connectorId] = hash
        _lastPushedInfo.value = info
        log(TAG, DEBUG) { "Pushed clipboard payload (${bytes.size}B) to ${connectorId.logLabel}" }
    }

    private suspend fun tryPullPeerClipboard(event: EventPayload.Event.ModuleChanged) {
        val targetDeviceId = DeviceId(event.deviceId)
        val result = graph.moduleReader.read(
            moduleId = ModuleIds.CLIPBOARD,
            targetDeviceId = targetDeviceId,
            serializer = ClipboardInfo.serializer(),
        )
        val info = when (result) {
            is ModuleReader.Result.Ok -> result.value
            else -> return
        }
        if (info.type != ClipboardInfo.Type.SIMPLE_TEXT) return
        val text = info.data.utf8()
        val hash = info.data.sha256()

        val sourceLabel = graph.deviceListRepo.devices.value
            .firstOrNull { it.id == event.deviceId }?.label
        _currentEntry.value = ClipboardEntry(
            sourceDeviceId = event.deviceId,
            sourceDeviceLabel = sourceLabel,
            text = text,
            receivedAt = Clock.System.now(),
        )

        // Only write to the local clipboard if the user opted in. The UI always shows the
        // incoming entry — applying it system-wide is the privileged action.
        if (graph.settings.data.clipboardAutoSync) {
            applyToLocalClipboard(text, hash)
        }
    }

    private fun applyToLocalClipboard(text: String, hash: ByteString) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            lastAppliedHash = hash
            log(TAG, DEBUG) { "Applied incoming clipboard payload (${text.length} chars) to local clipboard" }
        } catch (e: HeadlessException) {
            // Shouldn't happen — we gated start() on toolkit availability — but defend anyway.
        } catch (e: IllegalStateException) {
            // System clipboard busy / contention. Skip; the next inbound event will retry.
            log(TAG, WARN) { "Local clipboard busy; skipping apply" }
        } catch (e: Throwable) {
            log(TAG, WARN, e) { "Local clipboard apply failed" }
        }
    }

    /** Returns (text, sha256 of UTF-8 bytes) or null if no plain-string clipboard data exists. */
    private fun readLocalClipboardOrNull(): Pair<String, ByteString>? {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
            val text = clipboard.getData(DataFlavor.stringFlavor) as? String ?: return null
            val hash = text.toByteArray(Charsets.UTF_8).toByteString().sha256()
            text to hash
        } catch (_: HeadlessException) {
            null
        } catch (_: IllegalStateException) {
            // Clipboard owner busy (Windows + Wayland sometimes); try again next tick.
            null
        } catch (e: Throwable) {
            log(TAG, WARN, e) { "Local clipboard read failed" }
            null
        }
    }

    companion object {
        private val POLL_INTERVAL = 1.seconds
    }
}

/** What the UI displays as the "currently synced" clipboard. */
data class ClipboardEntry(
    val sourceDeviceId: String,
    val sourceDeviceLabel: String?,
    val text: String,
    val receivedAt: Instant,
)

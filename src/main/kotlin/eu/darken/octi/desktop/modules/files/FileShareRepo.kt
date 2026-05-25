package eu.darken.octi.desktop.modules.files

import eu.darken.octi.desktop.blob.BlobDownloader
import eu.darken.octi.desktop.blob.BlobUploader
import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.EncryptionMode
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.octiserver.dto.ModuleCommitRequest
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.protocol.sync.RemoteBlobRef
import eu.darken.octi.desktop.sync.ModuleReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import java.nio.file.Path
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private val TAG = logTag("Module", "Files", "Repo")

/**
 * Owns the desktop's own FileShareInfo document and exposes peer file lists.
 *
 * Reading peers: a thin wrapper over [ModuleReader] — caller supplies a peer deviceId, gets
 * back a `FileShareInfo?`. The WS-driven [eu.darken.octi.desktop.sync.SyncEventBus] subscription
 * here just nudges our own document refresh; per-peer caching/refresh lives in the UI
 * ViewModel (kept simple for MVP).
 *
 * Writing our own: [shareFile] uploads the blob via [BlobUploader] to every linked, non-paused
 * connector that has a GCM-SIV keyset (legacy SIV connectors are silently skipped — the
 * streaming cipher won't run on them). The resulting `SharedFile.connectorRefs` records each
 * connector that accepted the blob; peers see whichever copies they can reach. [commitOwn]
 * then fans the encrypted document out to every running connector with per-connector ETag and
 * per-connector keyset. Each commit is independent; a partial-success ends in
 * [ShareResult.Ok] as long as at least one connector got the blob AND at least one connector
 * accepted the document.
 */
class FileShareRepo(private val graph: AppGraph) {

    val downloader: BlobDownloader by lazy { BlobDownloader(graph) }
    val uploader: BlobUploader by lazy { BlobUploader() }

    private val ownDocLock = Mutex()
    private val _ownFiles = MutableStateFlow<FileShareInfo?>(null)
    val ownFiles: StateFlow<FileShareInfo?> = _ownFiles.asStateFlow()

    /**
     * Per-connector own-document state. Each connector tracks its own server-side ETag because
     * the FileShareInfo document is a per-server resource — a successful PUT to connector A
     * doesn't bump connector B's ETag.
     */
    private val ownDocStateByConnector: MutableMap<ConnectorId, OwnDocState> = mutableMapOf()

    private data class OwnDocState(val etag: String?)

    fun start() {
        // Pull our own document whenever the active-connector set changes (any first / additional
        // link, or unlink that still leaves at least one connector). ModuleResolver under the
        // facade picks the freshest doc across connectors, so we don't have to fan-out reads
        // here — one read covers all connectors that have the doc.
        graph.activeConnectors
            .onEach { if (it.isNotEmpty()) refreshOwn() }
            .launchIn(graph.appScope)

        graph.syncEventBus.events
            .filter { syncEvent ->
                val ev = syncEvent.event
                ev is EventPayload.Event.ModuleChanged &&
                    ev.moduleId == ModuleIds.FILES.id &&
                    ev.deviceId == graph.deviceId.id
            }
            .onEach { refreshOwn() }
            .launchIn(graph.appScope)

        // Prune per-connector ETag state when a connector leaves activeConnectors entirely
        // (unlink). Paused connectors stay in activeConnectors and keep their ETag so a
        // pause+resume round-trip doesn't need to re-fetch.
        graph.activeConnectors
            .onEach { connectors ->
                val activeIds = connectors.map { it.identifier }.toSet()
                ownDocStateByConnector.keys.retainAll(activeIds)
            }
            .launchIn(graph.appScope)
    }

    /** Refresh the desktop's own FileShareInfo from the server. Idempotent; safe to call often. */
    suspend fun refreshOwn() {
        when (val result = graph.moduleReader.read(
            moduleId = ModuleIds.FILES,
            targetDeviceId = graph.deviceId,
            serializer = FileShareInfo.serializer(),
        )) {
            is ModuleReader.Result.Ok -> {
                _ownFiles.value = result.value
                log(TAG, DEBUG) { "Refreshed own FileShareInfo (${result.value.files.size} files)" }
            }
            ModuleReader.Result.NotFound -> {
                _ownFiles.value = FileShareInfo()
                log(TAG, DEBUG) { "No own FileShareInfo on server yet; treating as empty" }
            }
            is ModuleReader.Result.Error -> {
                log(TAG, WARN) { "Refresh of own FileShareInfo failed: ${result.cause.message}" }
            }
        }
    }

    /**
     * Reads a peer's FileShareInfo. The UI is expected to call this on demand (when entering
     * the per-peer Files screen) rather than the repo polling every peer eagerly — that would
     * burn rate-limit budget for data the user isn't viewing.
     */
    suspend fun readPeer(peer: DeviceId): FileShareInfo? = when (
        val result = graph.moduleReader.read(
            moduleId = ModuleIds.FILES,
            targetDeviceId = peer,
            serializer = FileShareInfo.serializer(),
        )
    ) {
        is ModuleReader.Result.Ok -> result.value
        ModuleReader.Result.NotFound -> FileShareInfo()
        is ModuleReader.Result.Error -> {
            log(TAG, WARN) { "Peer ${peer.logLabel} FileShareInfo read failed: ${result.cause.message}" }
            null
        }
    }

    /**
     * Share a local file. Uploads the blob to every eligible connector (running + GCM-SIV
     * keyset), records the successful uploads in [FileShareInfo.SharedFile.connectorRefs], and
     * commits the updated document to every running connector.
     *
     * Eligibility: a connector is eligible for blob upload only if its keyset is GCM-SIV —
     * [BlobUploader] refuses legacy SIV. Connectors mixed legacy/GCM-SIV mean the blob lives
     * only on the GCM-SIV subset; the SharedFile.connectorRefs / .availableOn fields surface
     * that to peers.
     */
    suspend fun shareFile(
        sourceFile: Path,
        displayName: String? = null,
        mimeType: String = "application/octet-stream",
    ): ShareResult {
        val targets = graph.runningConnectors.value
        if (targets.isEmpty()) return ShareResult.NoCredentials

        val gcmSivTargets = targets.filter {
            EncryptionMode.fromTypeString(it.credentials.encryptionKeyset.type) == EncryptionMode.AES256_GCM_SIV
        }
        if (gcmSivTargets.isEmpty()) {
            log(TAG, WARN) {
                "No GCM-SIV connector available for blob upload; ${targets.size} running connector(s) are all on the legacy keyset"
            }
            return ShareResult.LegacyKeysetNotSupported
        }

        val blobKey = UUID.randomUUID().toString()
        val refs = mutableMapOf<String, RemoteBlobRef>()
        var plaintextChecksum: String? = null
        var lastUploadFailure: BlobUploader.Result? = null

        for (connector in gcmSivTargets) {
            val outcome = uploader.upload(
                connector = connector,
                ownerDeviceId = graph.deviceId,
                moduleId = ModuleIds.FILES,
                blobKey = blobKey,
                sourceFile = sourceFile,
            )
            when (outcome) {
                is BlobUploader.Result.Ok -> {
                    refs[connector.identifier.idString] = RemoteBlobRef(outcome.serverBlobId)
                    plaintextChecksum = outcome.plaintextChecksumHex
                    log(TAG, DEBUG) {
                        "Uploaded blob to ${connector.identifier.logLabel} (serverBlobId=${outcome.serverBlobId})"
                    }
                }
                else -> {
                    lastUploadFailure = outcome
                    log(TAG, WARN) {
                        "Blob upload to ${connector.identifier.logLabel} failed: ${outcome::class.simpleName}"
                    }
                }
            }
        }

        if (refs.isEmpty()) {
            return ShareResult.UploadFailed(lastUploadFailure ?: BlobUploader.Result.NoClient)
        }

        val sharedFile = FileShareInfo.SharedFile(
            name = displayName ?: sourceFile.fileName.toString(),
            mimeType = mimeType,
            size = sourceFile.toFile().length(),
            blobKey = blobKey,
            checksum = checkNotNull(plaintextChecksum) { "At least one upload succeeded — checksum must be set" },
            sharedAt = Clock.System.now(),
            expiresAt = Clock.System.now() + DEFAULT_EXPIRY,
            availableOn = refs.keys.toSet(),
            connectorRefs = refs.toMap(),
        )

        return ownDocLock.withLock {
            val current = _ownFiles.value ?: FileShareInfo()
            val updated = current.copy(files = current.files + sharedFile)
            val commitOutcome = commitOwnEverywhere(updated)
            if (commitOutcome.anySuccess) {
                _ownFiles.value = updated
                log(TAG, INFO) {
                    "Shared file ${sharedFile.name} (blobKey=$blobKey) — blob on ${refs.size} " +
                        "connector(s), document on ${commitOutcome.successCount} connector(s)"
                }
                ShareResult.Ok(sharedFile, committedConnectorCount = commitOutcome.successCount)
            } else {
                log(TAG, WARN, commitOutcome.firstFailure) { "All FileShareInfo commits failed" }
                ShareResult.CommitFailed(
                    commitOutcome.firstFailure ?: IllegalStateException("All commits failed without a cause"),
                )
            }
        }
    }

    /**
     * Append a deletion request for a peer-owned blob. Fans the updated document out to every
     * running connector — the owning device will pick it up via whichever it polls. (Codex
     * review #3: don't call DELETE /blobs/{id} directly; respect the module's deleteRequests
     * lifecycle.)
     */
    suspend fun requestDeletion(targetDeviceId: DeviceId, blobKey: String): ShareResult {
        if (graph.runningConnectors.value.isEmpty()) return ShareResult.NoCredentials
        return ownDocLock.withLock {
            val current = _ownFiles.value ?: FileShareInfo()
            val request = FileShareInfo.DeleteRequest(
                targetDeviceId = targetDeviceId.id,
                blobKey = blobKey,
                requestedAt = Clock.System.now(),
                retainUntil = Clock.System.now() + DEFAULT_DELETION_RETAIN,
            )
            val updated = current.copy(deleteRequests = current.deleteRequests + request)
            val commitOutcome = commitOwnEverywhere(updated)
            if (commitOutcome.anySuccess) {
                _ownFiles.value = updated
                ShareResult.Ok(sharedFile = null, committedConnectorCount = commitOutcome.successCount)
            } else {
                log(TAG, WARN, commitOutcome.firstFailure) { "Deletion-request commit failed on every connector" }
                ShareResult.CommitFailed(
                    commitOutcome.firstFailure ?: IllegalStateException("All commits failed without a cause"),
                )
            }
        }
    }

    private data class CommitOutcome(
        val successCount: Int,
        val firstFailure: Throwable?,
    ) {
        val anySuccess: Boolean get() = successCount > 0
    }

    /**
     * Fan the encrypted document out to every running connector. Each connector encrypts with
     * its own keyset and tracks its own ETag — partial success leaves [_ownFiles] (local view)
     * in sync with the most-recent intended state; peers reading a connector where the commit
     * failed will see the stale doc until the next refresh.
     */
    private suspend fun commitOwnEverywhere(info: FileShareInfo): CommitOutcome {
        val targets = graph.runningConnectors.value
        var success = 0
        var firstFailure: Throwable? = null
        for (connector in targets) {
            try {
                commitOwn(info, connector)
                success++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                if (firstFailure == null) firstFailure = e
                log(TAG, WARN, e) { "commitOwn to ${connector.identifier.logLabel} failed; continuing with the rest" }
            }
        }
        return CommitOutcome(successCount = success, firstFailure = firstFailure)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun commitOwn(info: FileShareInfo, connector: OctiServerConnector) {
        val client = connector.client
        val credentials = connector.credentials
        val crypto = PayloadEncryption(keySet = credentials.encryptionKeyset)
        val plaintext = Serialization.json.encodeToString(FileShareInfo.serializer(), info)
            .toByteArray(Charsets.UTF_8)
        // Android wire format: gzip then encrypt with AAD = "${deviceId}:${moduleId}".
        val aad = "${graph.deviceId.id}:${ModuleIds.FILES.id}".toByteArray(Charsets.UTF_8)
        val ciphertext = crypto.encrypt(plaintext.toByteString().toGzip(), aad).toByteArray()
        val documentBase64 = Base64.Default.encode(ciphertext)

        val connectorIdString = connector.identifier.idString
        // Only include blob refs that this specific connector accepted — connectors that don't
        // have the blob would fail the server-side ref check.
        val blobRefs = info.files
            .mapNotNull { it.connectorRefs[connectorIdString]?.value }
            .distinct()
            .map { ModuleCommitRequest.BlobRef(blobId = it) }

        val connectorId = connector.identifier
        val currentEtag = ownDocStateByConnector[connectorId]?.etag

        client.commitModule(
            moduleId = ModuleIds.FILES,
            request = ModuleCommitRequest(documentBase64 = documentBase64, blobRefs = blobRefs),
            ifMatch = currentEtag,
            ifNoneMatch = if (currentEtag == null) "*" else null,
        )

        // We don't get the new ETag back from commitModule — refresh to pick it up so the
        // next commit's If-Match matches the new server state.
        refreshOwnEtag(connector)
    }

    private suspend fun refreshOwnEtag(connector: OctiServerConnector) {
        try {
            when (val response = connector.client.readModule(ModuleIds.FILES, graph.deviceId)) {
                is eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient.ModuleReadResult.Ok -> {
                    ownDocStateByConnector[connector.identifier] = OwnDocState(etag = response.etag)
                }
                else -> Unit
            }
        } catch (e: Throwable) {
            log(TAG, WARN, e) { "Failed to refresh own FileShareInfo ETag for ${connector.identifier.logLabel}" }
        }
    }

    sealed class ShareResult {
        /**
         * [committedConnectorCount] is how many connectors accepted the FileShareInfo document
         * PUT — distinct from the blob fan-out count carried by `sharedFile.connectorRefs`. A
         * partial success (blob on both servers, document on one) still reports
         * [ShareResult.Ok]; the counts let a caller tell the two fan-outs apart.
         */
        data class Ok(
            val sharedFile: FileShareInfo.SharedFile?,
            val committedConnectorCount: Int,
        ) : ShareResult()
        data object NoCredentials : ShareResult()
        data object LegacyKeysetNotSupported : ShareResult()
        data class UploadFailed(val cause: BlobUploader.Result) : ShareResult()
        data class CommitFailed(val cause: Throwable) : ShareResult()
    }

    companion object {
        private val DEFAULT_EXPIRY = 30.days
        private val DEFAULT_DELETION_RETAIN = 7.days
    }
}

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
 * Writing our own: [shareFile] uploads the blob via [BlobUploader], builds a [SharedFile]
 * entry referencing the resulting server blob id, appends it to the in-memory document, and
 * PUTs the encrypted document via [commitOwn]. The PUT is ETag-conditional (`If-Match` /
 * `If-None-Match: *`) — racing two desktop instances on the same account is rejected by the
 * server rather than silently losing one writer's edit.
 */
class FileShareRepo(private val graph: AppGraph) {

    val downloader: BlobDownloader by lazy { BlobDownloader(graph) }
    val uploader: BlobUploader by lazy { BlobUploader(graph) }

    private val ownDocLock = Mutex()
    private val _ownFiles = MutableStateFlow<FileShareInfo?>(null)
    val ownFiles: StateFlow<FileShareInfo?> = _ownFiles.asStateFlow()

    /**
     * Per-connector own-document state. Each connector tracks its own server-side ETag because
     * the FileShareInfo document is a per-server resource — a successful PUT to connector A
     * doesn't bump connector B's ETag. Today only the primary connector receives commits, so
     * the map has at most one entry; PR-5's blob fan-out reads its entry per connector.
     */
    private val ownDocStateByConnector: MutableMap<ConnectorId, OwnDocState> = mutableMapOf()

    private data class OwnDocState(val etag: String?)

    fun start() {
        // Pull our own document on every transition into an active connector + on every WS
        // ModuleChanged that targets our own files document (peer issued a deleteRequest
        // against us, etc.).
        graph.primaryConnector
            .onEach { connector -> if (connector != null) refreshOwn() }
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

    /** Share a local file. Uploads the blob then commits the updated document. */
    suspend fun shareFile(
        sourceFile: Path,
        displayName: String? = null,
        mimeType: String = "application/octet-stream",
    ): ShareResult {
        val connector = graph.primaryConnector.value ?: return ShareResult.NoCredentials
        val credentials = connector.credentials
        val blobKey = UUID.randomUUID().toString()

        val uploadResult = uploader.upload(
            ownerDeviceId = graph.deviceId,
            moduleId = ModuleIds.FILES,
            blobKey = blobKey,
            sourceFile = sourceFile,
        )
        val upload = when (uploadResult) {
            is BlobUploader.Result.Ok -> uploadResult
            BlobUploader.Result.LegacyKeysetNotSupported -> return ShareResult.LegacyKeysetNotSupported
            else -> return ShareResult.UploadFailed(uploadResult)
        }

        val connectorIdString = connector.identifier.idString
        val sharedFile = FileShareInfo.SharedFile(
            name = displayName ?: sourceFile.fileName.toString(),
            mimeType = mimeType,
            size = sourceFile.toFile().length(),
            blobKey = blobKey,
            checksum = upload.plaintextChecksumHex,
            sharedAt = Clock.System.now(),
            expiresAt = Clock.System.now() + DEFAULT_EXPIRY,
            availableOn = setOf(connectorIdString),
            connectorRefs = mapOf(connectorIdString to RemoteBlobRef(upload.serverBlobId)),
        )

        return ownDocLock.withLock {
            val current = _ownFiles.value ?: FileShareInfo()
            val updated = current.copy(files = current.files + sharedFile)
            try {
                commitOwn(updated, connector)
                _ownFiles.value = updated
                log(TAG, INFO) { "Shared file ${sharedFile.name} (blobKey=$blobKey)" }
                ShareResult.Ok(sharedFile)
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "Commit of new FileShareInfo failed" }
                ShareResult.CommitFailed(e)
            }
        }
    }

    /**
     * Append a deletion request for a peer-owned blob. The owning device picks this up on its
     * next sync round and physically removes the blob (Codex review #3: don't call DELETE
     * /blobs/{id} directly; respect the module's deleteRequests lifecycle).
     */
    suspend fun requestDeletion(targetDeviceId: DeviceId, blobKey: String): ShareResult {
        val connector = graph.primaryConnector.value ?: return ShareResult.NoCredentials
        return ownDocLock.withLock {
            val current = _ownFiles.value ?: FileShareInfo()
            val request = FileShareInfo.DeleteRequest(
                targetDeviceId = targetDeviceId.id,
                blobKey = blobKey,
                requestedAt = Clock.System.now(),
                retainUntil = Clock.System.now() + DEFAULT_DELETION_RETAIN,
            )
            val updated = current.copy(deleteRequests = current.deleteRequests + request)
            try {
                commitOwn(updated, connector)
                _ownFiles.value = updated
                ShareResult.Ok(sharedFile = null)
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "Commit of deleteRequest failed" }
                ShareResult.CommitFailed(e)
            }
        }
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
        data class Ok(val sharedFile: FileShareInfo.SharedFile?) : ShareResult()
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

package eu.darken.octi.desktop.ui.files

import eu.darken.octi.desktop.blob.BlobDownloader
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.modules.files.FileShareRepo
import eu.darken.octi.desktop.protocol.encryption.EncryptionMode
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths

private val TAG = logTag("UI", "Files")

/**
 * State holder for [FilesScreen]. Distinguishes "self" (this desktop's own files — supports
 * Share + Remove) from "peer" (read-only list with Download + Request-Deletion).
 *
 * Per-file action progress is held in [FilesUiState.actions] so the UI can show a per-row
 * spinner without coupling all rows together.
 */
class FilesViewModel(
    private val graph: AppGraph,
    val targetDeviceId: DeviceId,
) {

    val isSelf: Boolean = targetDeviceId.id == graph.deviceId.id

    private val _state = MutableStateFlow(FilesUiState(loading = true))
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = graph.appScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val info = if (isSelf) {
                    graph.fileShareRepo.refreshOwn()
                    graph.fileShareRepo.ownFiles.value ?: FileShareInfo()
                } else {
                    graph.fileShareRepo.readPeer(targetDeviceId) ?: FileShareInfo()
                }
                _state.value = FilesUiState(
                    loading = false,
                    files = info.files,
                    legacyKeysetBlocksUpload = isLegacyKeyset(),
                )
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "Refresh failed" }
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    fun share(sourceFile: Path) {
        if (!isSelf) return
        graph.appScope.launch {
            setAction(sourceFile.fileName.toString(), Action.Uploading)
            val result = graph.fileShareRepo.shareFile(sourceFile = sourceFile)
            val outcome = when (result) {
                is FileShareRepo.ShareResult.Ok -> Action.Done
                FileShareRepo.ShareResult.LegacyKeysetNotSupported -> Action.Failed(
                    "File sharing needs the newer encryption mode. Open Octi on your phone and re-link this device.",
                )
                is FileShareRepo.ShareResult.UploadFailed -> Action.Failed(
                    "Upload failed: ${result.cause.javaClass.simpleName}",
                )
                is FileShareRepo.ShareResult.CommitFailed -> Action.Failed(
                    "Document commit failed: ${result.cause.message}",
                )
                FileShareRepo.ShareResult.NoCredentials -> Action.Failed("Not linked.")
            }
            setAction(sourceFile.fileName.toString(), outcome)
            refresh()
        }
    }

    fun download(file: FileShareInfo.SharedFile, destination: Path) {
        val connectorIdString = currentConnectorIdString() ?: return
        val serverBlobId = file.connectorRefs[connectorIdString]?.value ?: run {
            setAction(file.blobKey, Action.Failed("This connector doesn't have a copy of the blob"))
            return
        }
        graph.appScope.launch {
            setAction(file.blobKey, Action.Downloading)
            val result = graph.fileShareRepo.downloader.download(
                ownerDeviceId = targetDeviceId,
                moduleId = ModuleIds.FILES,
                blobKey = file.blobKey,
                serverBlobId = serverBlobId,
                expectedChecksumHex = file.checksum,
                destinationFile = destination,
            )
            val outcome = when (result) {
                is BlobDownloader.Result.Ok -> Action.Done
                is BlobDownloader.Result.ChecksumMismatch -> Action.Failed(
                    "Checksum mismatch — file may be corrupted",
                )
                is BlobDownloader.Result.HttpError -> Action.Failed("Network error: ${result.cause.message}")
                is BlobDownloader.Result.DecryptionFailed -> Action.Failed("Decryption failed — keyset mismatch?")
                BlobDownloader.Result.NoClient -> Action.Failed("Not connected")
                BlobDownloader.Result.NoCredentials -> Action.Failed("Not linked")
            }
            setAction(file.blobKey, outcome)
        }
    }

    fun requestDeletion(file: FileShareInfo.SharedFile) {
        graph.appScope.launch {
            setAction(file.blobKey, Action.Deleting)
            val result = graph.fileShareRepo.requestDeletion(targetDeviceId, file.blobKey)
            val outcome = when (result) {
                is FileShareRepo.ShareResult.Ok -> Action.Done
                is FileShareRepo.ShareResult.CommitFailed -> Action.Failed(
                    "Deletion request failed: ${result.cause.message}",
                )
                else -> Action.Failed("Unable to request deletion")
            }
            setAction(file.blobKey, outcome)
        }
    }

    /** Default download destination — host's Downloads folder. */
    fun defaultDownloadFor(file: FileShareInfo.SharedFile): Path {
        val downloads = Paths.get(System.getProperty("user.home"), "Downloads")
        return downloads.resolve(file.name)
    }

    private fun setAction(key: String, action: Action) {
        _state.value = _state.value.copy(actions = _state.value.actions + (key to action))
    }

    private fun isLegacyKeyset(): Boolean {
        val credentials = graph.credentialsStore.load() ?: return false
        return EncryptionMode.fromTypeString(credentials.encryptionKeyset.type) != EncryptionMode.AES256_GCM_SIV
    }

    private fun currentConnectorIdString(): String? {
        val credentials = graph.credentialsStore.load() ?: return null
        return ConnectorId(
            type = ConnectorType.OCTISERVER,
            subtype = credentials.serverAdress.domain,
            account = credentials.accountId.id,
        ).idString
    }

}

data class FilesUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val files: List<FileShareInfo.SharedFile> = emptyList(),
    val legacyKeysetBlocksUpload: Boolean = false,
    val actions: Map<String, Action> = emptyMap(),
)

sealed class Action {
    data object Uploading : Action()
    data object Downloading : Action()
    data object Deleting : Action()
    data object Done : Action()
    data class Failed(val message: String) : Action()
}

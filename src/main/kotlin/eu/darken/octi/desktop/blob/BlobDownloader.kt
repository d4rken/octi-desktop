package eu.darken.octi.desktop.blob

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.encryption.StreamingPayloadCipher
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val TAG = logTag("Blob", "Downloader")

/**
 * Downloads an encrypted blob from one of multiple candidate connectors, decrypts it to a
 * temp file, verifies the plaintext SHA-256, and atomically renames it into place. Codex
 * review #4: deliberately no `Range` support — Tink's streaming AEAD can't safely expose
 * partial plaintext, and a download-then-decrypt path keeps the implementation simple.
 * Re-download is fast enough to make resume not worth the risk.
 *
 * Multi-source: when the same blob is published on more than one connector (PR-5 fan-out),
 * the caller passes the full `(ConnectorId → serverBlobId)` map. We try sources in iteration
 * order; the first one that returns a body which decrypts AND matches the expected plaintext
 * checksum wins. Per-source failures are logged and we move on; only when every source has
 * been exhausted do we return an error.
 *
 * On any failure the temp file is removed; the destination is never partially written.
 */
class BlobDownloader(private val graph: AppGraph) {

    sealed class Result {
        /** [source] is the connector that actually served the blob (useful for telemetry). */
        data class Ok(val finalPath: Path, val sizeBytes: Long, val source: ConnectorId) : Result()
        data class ChecksumMismatch(val expectedHex: String, val actualHex: String) : Result()
        data object NoClient : Result()
        data object NoCredentials : Result()
        data class HttpError(val cause: Throwable) : Result()
        data class DecryptionFailed(val cause: Throwable) : Result()
    }

    /**
     * @param ownerDeviceId the peer that produced the blob (used in the AAD).
     * @param moduleId always `files` for the MVP; broader use lands when more blob-bearing
     *   modules show up.
     * @param blobKey the client-side logical id from `FileShareInfo.SharedFile.blobKey`.
     * @param sources ordered map of `(ConnectorId → serverBlobId)`. Iteration order is the
     *   preference order — caller typically lists the primary / most-likely-reachable first.
     *   At least one entry must correspond to an active [OctiServerConnector] or we return
     *   [Result.NoClient].
     * @param expectedChecksumHex hex-encoded SHA-256 of the plaintext (from `SharedFile.checksum`).
     * @param destinationFile final on-disk path. Parent directory is created if missing; the
     *   final file is written atomically (temp + rename).
     */
    suspend fun download(
        ownerDeviceId: DeviceId,
        moduleId: ModuleId,
        blobKey: String,
        sources: Map<ConnectorId, String>,
        expectedChecksumHex: String,
        destinationFile: Path,
    ): Result {
        val activeById = graph.activeConnectors.value.associateBy { it.identifier }
        val candidates = sources.mapNotNull { (id, blobId) ->
            activeById[id]?.let { it to blobId }
        }
        if (candidates.isEmpty()) {
            log(TAG, Logging.Priority.WARN) {
                "Download: no candidate sources are active (sources=${sources.keys.map { it.idString }})"
            }
            return Result.NoClient
        }

        Files.createDirectories(destinationFile.parent)

        var lastFailure: Result = Result.NoClient
        for ((connector, serverBlobId) in candidates) {
            val tempFile = destinationFile.resolveSibling(
                ".${destinationFile.fileName}.dl.${connector.identifier.idString.hashCode()}.${System.nanoTime()}",
            )
            val outcome = try {
                tryDownloadFrom(
                    connector = connector,
                    ownerDeviceId = ownerDeviceId,
                    moduleId = moduleId,
                    blobKey = blobKey,
                    serverBlobId = serverBlobId,
                    expectedChecksumHex = expectedChecksumHex,
                    destinationFile = destinationFile,
                    tempFile = tempFile,
                )
            } catch (cancel: CancellationException) {
                runCatching { Files.deleteIfExists(tempFile) }
                throw cancel
            } catch (e: Throwable) {
                runCatching { Files.deleteIfExists(tempFile) }
                log(TAG, Logging.Priority.WARN, e) {
                    "Unexpected error downloading from ${connector.identifier.logLabel}; trying next source"
                }
                Result.HttpError(e)
            }
            if (outcome is Result.Ok) return outcome
            lastFailure = outcome
            log(TAG, Logging.Priority.DEBUG) {
                "Download from ${connector.identifier.logLabel} failed (${outcome::class.simpleName}); " +
                    "${candidates.size - (candidates.indexOf(connector to serverBlobId) + 1)} candidate(s) remain"
            }
        }
        log(TAG, Logging.Priority.WARN) {
            "Exhausted ${candidates.size} download source(s) for blobKey=$blobKey; surfacing last failure"
        }
        return lastFailure
    }

    private suspend fun tryDownloadFrom(
        connector: OctiServerConnector,
        ownerDeviceId: DeviceId,
        moduleId: ModuleId,
        blobKey: String,
        serverBlobId: String,
        expectedChecksumHex: String,
        destinationFile: Path,
        tempFile: Path,
    ): Result {
        val cipher = StreamingPayloadCipher(connector.credentials.encryptionKeyset)
        val aad = cipher.aadFor(
            deviceId = ownerDeviceId.id,
            moduleId = moduleId.id,
            blobKey = blobKey,
        )

        val ciphertextBytes = try {
            connector.client.getBlobBytes(moduleId, serverBlobId, ownerDeviceId)
        } catch (e: Throwable) {
            log(TAG, Logging.Priority.WARN, e) {
                "GET blob $serverBlobId from ${connector.identifier.logLabel} failed"
            }
            return Result.HttpError(e)
        }
        log(TAG, Logging.Priority.DEBUG) {
            "Downloaded ${ciphertextBytes.size}B ciphertext for blob=$serverBlobId from ${connector.identifier.logLabel}"
        }

        val plaintextSink = Files.newOutputStream(tempFile).sink().buffer()
        try {
            cipher.decrypt(
                source = Buffer().write(ciphertextBytes),
                sink = plaintextSink,
                associatedData = aad,
            )
        } catch (e: Throwable) {
            log(TAG, Logging.Priority.WARN, e) {
                "decrypt failed for blob=$serverBlobId from ${connector.identifier.logLabel}"
            }
            Files.deleteIfExists(tempFile)
            return Result.DecryptionFailed(e)
        } finally {
            runCatching { plaintextSink.close() }
        }

        val actualChecksum = sha256Hex(tempFile)
        if (!actualChecksum.equals(expectedChecksumHex, ignoreCase = true)) {
            log(TAG, Logging.Priority.WARN) {
                "Checksum mismatch for blob=$serverBlobId from ${connector.identifier.logLabel}: " +
                    "expected=$expectedChecksumHex actual=$actualChecksum"
            }
            Files.deleteIfExists(tempFile)
            return Result.ChecksumMismatch(expectedChecksumHex, actualChecksum)
        }

        Files.move(
            tempFile,
            destinationFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val size = Files.size(destinationFile)
        log(TAG, Logging.Priority.INFO) {
            "Blob $serverBlobId saved to $destinationFile (${size}B) via ${connector.identifier.logLabel}"
        }
        return Result.Ok(destinationFile, size, connector.identifier)
    }

    private fun sha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).source().buffer().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf, 0, buf.size)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

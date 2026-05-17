package eu.darken.octi.desktop.blob

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.encryption.StreamingPayloadCipher
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.sync.DeviceId
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
 * Downloads an encrypted blob, decrypts it to a temp file, verifies the plaintext SHA-256, and
 * atomically renames it into place. Codex review #4: deliberately no `Range` support — Tink's
 * streaming AEAD can't safely expose partial plaintext, and a download-then-decrypt path keeps
 * the implementation simple. Re-download is fast enough to make resume not worth the risk.
 *
 * On any failure the temp file is removed; the destination is never partially written.
 */
class BlobDownloader(private val graph: AppGraph) {

    sealed class Result {
        data class Ok(val finalPath: Path, val sizeBytes: Long) : Result()
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
     * @param serverBlobId the opaque id from `connectorRefs[octiserver.idString]`.
     * @param expectedChecksumHex hex-encoded SHA-256 of the plaintext (from `SharedFile.checksum`).
     * @param destinationFile final on-disk path. Parent directory is created if missing; the
     *   final file is written atomically (temp + rename).
     */
    suspend fun download(
        ownerDeviceId: DeviceId,
        moduleId: ModuleId,
        blobKey: String,
        serverBlobId: String,
        expectedChecksumHex: String,
        destinationFile: Path,
    ): Result {
        val client = graph.activeClient.value ?: return Result.NoClient
        val credentials = graph.credentialsStore.load() ?: return Result.NoCredentials

        Files.createDirectories(destinationFile.parent)
        val tempFile = destinationFile.resolveSibling(".${destinationFile.fileName}.dl.${System.nanoTime()}")

        return try {
            val cipher = StreamingPayloadCipher(credentials.encryptionKeyset)
            val aad = cipher.aadFor(
                deviceId = ownerDeviceId.id,
                moduleId = moduleId.id,
                blobKey = blobKey,
            )

            // Pull the ciphertext into a Buffer. We could stream straight from the response
            // body to the cipher, but Ktor's body APIs need to be consumed within the call's
            // scope; buffering keeps the structure linear and lets us retry decrypt without a
            // re-fetch if the keystore had a transient issue.
            val ciphertextBytes = try {
                client.getBlobBytes(moduleId, serverBlobId, ownerDeviceId)
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.WARN, e) { "GET blob $serverBlobId failed" }
                return Result.HttpError(e)
            }
            log(TAG, Logging.Priority.DEBUG) {
                "Downloaded ${ciphertextBytes.size}B ciphertext for blob=$serverBlobId"
            }

            // Stream-decrypt to the temp file. Tink emits plaintext segment-by-segment; if a
            // later tag fails, the temp file already contains earlier segments — that's why
            // the contract says to use a temp + checksum re-verify before moving into place.
            val plaintextSink = Files.newOutputStream(tempFile).sink().buffer()
            try {
                cipher.decrypt(
                    source = Buffer().write(ciphertextBytes),
                    sink = plaintextSink,
                    associatedData = aad,
                )
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.WARN, e) { "decrypt failed for blob=$serverBlobId" }
                Files.deleteIfExists(tempFile)
                return Result.DecryptionFailed(e)
            } finally {
                runCatching { plaintextSink.close() }
            }

            // Plaintext checksum verify. Even if decrypt() succeeded, we want one more
            // defensive check that the bytes we'd hand to the user match what the producing
            // device claimed — guards against ciphertext-level bit-flips on segment boundaries
            // (Tink's tag covers segments individually; this catches cross-segment tampering).
            val actualChecksum = sha256Hex(tempFile)
            if (!actualChecksum.equals(expectedChecksumHex, ignoreCase = true)) {
                log(TAG, Logging.Priority.WARN) {
                    "Checksum mismatch for blob=$serverBlobId: expected=$expectedChecksumHex actual=$actualChecksum"
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
            log(TAG, Logging.Priority.INFO) { "Blob $serverBlobId saved to $destinationFile ($size B)" }
            Result.Ok(destinationFile, size)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tempFile) }
            log(TAG, Logging.Priority.WARN, e) { "Unexpected error during download of $serverBlobId" }
            Result.HttpError(e)
        }
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

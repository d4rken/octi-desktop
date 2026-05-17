package eu.darken.octi.desktop.blob

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.encryption.EncryptionMode
import eu.darken.octi.desktop.protocol.encryption.StreamingPayloadCipher
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.dto.CreateSessionRequest
import eu.darken.octi.desktop.protocol.octiserver.dto.FinalizeSessionRequest
import eu.darken.octi.desktop.protocol.sync.DeviceId
import okio.buffer
import okio.sink
import okio.source
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private val TAG = logTag("Blob", "Uploader")

/**
 * Uploads a local file as a blob to the OctiServer for the active account.
 *
 * Steps:
 *  1. Encrypt the file into a temp ciphertext file using [StreamingPayloadCipher] with AAD
 *     `${deviceId}:${moduleId}:${blobKey}`.
 *  2. SHA-256 plaintext (end-to-end integrity, embedded in `FileShareInfo.SharedFile.checksum`)
 *     and SHA-256 ciphertext (transport integrity, sent in the create-session request and
 *     verified by the server at finalize).
 *  3. `POST /v1/module/{moduleId}/blob-sessions` with the ciphertext size + hash.
 *  4. `PATCH /v1/module/{moduleId}/blob-sessions/{sessionId}` with 1 MiB chunks and an
 *     `Upload-Offset` header. **On any error**: re-probe the session via `HEAD` to learn the
 *     server's current offset and resume from there. Never blind-retry the same range
 *     (Codex review #10 — would cause a 409 if the server already accepted the chunk).
 *  5. `POST .../finalize` with the ciphertext hash. Server compares against what it received
 *     and either issues a blob id or returns 422.
 *
 * Returns the server's blob id (for FileShareInfo.SharedFile.connectorRefs) plus the plaintext
 * checksum (for FileShareInfo.SharedFile.checksum).
 */
class BlobUploader(private val graph: AppGraph) {

    sealed class Result {
        data class Ok(
            val serverBlobId: String,
            val plaintextChecksumHex: String,
            val ciphertextSizeBytes: Long,
        ) : Result()

        data object NoClient : Result()
        data object NoCredentials : Result()
        data object LegacyKeysetNotSupported : Result()
        data class EncryptionFailed(val cause: Throwable) : Result()
        data class HttpError(val cause: Throwable) : Result()
        data class ChecksumRejectedByServer(val message: String?) : Result()
    }

    suspend fun upload(
        ownerDeviceId: DeviceId,
        moduleId: ModuleId,
        blobKey: String,
        sourceFile: Path,
    ): Result {
        val client = graph.activeClient.value ?: return Result.NoClient
        val credentials = graph.credentialsStore.load() ?: return Result.NoCredentials

        val mode = EncryptionMode.fromTypeString(credentials.encryptionKeyset.type)
        if (mode != EncryptionMode.AES256_GCM_SIV) {
            log(TAG, WARN) { "Refusing upload: legacy keyset (${credentials.encryptionKeyset.type})" }
            return Result.LegacyKeysetNotSupported
        }

        val cipher = StreamingPayloadCipher(credentials.encryptionKeyset)
        val aad = cipher.aadFor(ownerDeviceId.id, moduleId.id, blobKey)

        val ciphertextFile = Files.createTempFile("octi-blob-", ".enc")
        try {
            // 1. Encrypt to temp + compute both hashes.
            val plaintextChecksum: String
            val ciphertextChecksum: String
            try {
                Files.newInputStream(sourceFile).source().use { inSrc ->
                    Files.newOutputStream(ciphertextFile).sink().use { outSink ->
                        cipher.encrypt(inSrc, outSink, aad)
                    }
                }
                plaintextChecksum = sha256Hex(sourceFile)
                ciphertextChecksum = sha256Hex(ciphertextFile)
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "Streaming encrypt of $sourceFile failed" }
                return Result.EncryptionFailed(e)
            }

            val ciphertextSize = Files.size(ciphertextFile)
            log(TAG, DEBUG) {
                "Encrypted $sourceFile → ${ciphertextSize}B ciphertext (plaintext sha256=$plaintextChecksum)"
            }

            // 2. Create session.
            val session = try {
                client.createBlobSession(
                    moduleId = moduleId,
                    targetDeviceId = ownerDeviceId,
                    request = CreateSessionRequest(
                        sizeBytes = ciphertextSize,
                        hashAlgorithm = HASH_ALGORITHM,
                        hashHex = ciphertextChecksum,
                    ),
                )
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "createBlobSession failed for blobKey=$blobKey" }
                return Result.HttpError(e)
            }

            // 3. PATCH chunks with offset-probing retry.
            var offset = session.offsetBytes
            var attemptsForThisOffset = 0
            RandomAccessFile(ciphertextFile.toFile(), "r").use { raf ->
                while (offset < ciphertextSize) {
                    val chunkLen = (CHUNK_SIZE.toLong()).coerceAtMost(ciphertextSize - offset).toInt()
                    val chunk = ByteArray(chunkLen)
                    raf.seek(offset)
                    raf.readFully(chunk)
                    try {
                        client.appendBlobChunk(
                            moduleId = moduleId,
                            sessionId = session.sessionId,
                            targetDeviceId = ownerDeviceId,
                            offset = offset,
                            chunk = chunk,
                        )
                        offset += chunkLen
                        attemptsForThisOffset = 0
                    } catch (e: Throwable) {
                        attemptsForThisOffset++
                        if (attemptsForThisOffset >= MAX_RETRIES_PER_CHUNK) {
                            log(TAG, WARN, e) {
                                "Giving up on chunk @offset=$offset after $attemptsForThisOffset attempts"
                            }
                            runCatching { client.abortBlobSession(moduleId, session.sessionId, ownerDeviceId) }
                            return Result.HttpError(e)
                        }
                        // Probe server's current offset; the failure may have happened after
                        // the chunk was accepted. Resuming from the probed offset avoids 409s.
                        val probed = try {
                            client.headBlobSession(moduleId, session.sessionId, ownerDeviceId)
                        } catch (probeError: Throwable) {
                            log(TAG, WARN, probeError) {
                                "HEAD probe failed; can't safely retry chunk @offset=$offset"
                            }
                            return Result.HttpError(probeError)
                        }
                        log(TAG, DEBUG) {
                            "Chunk @offset=$offset failed (${e.javaClass.simpleName}); server reports offset=${probed.uploadOffset}, resuming"
                        }
                        offset = probed.uploadOffset
                    }
                }
            }

            // 4. Finalize.
            val finalized = try {
                client.finalizeBlobSession(
                    moduleId = moduleId,
                    sessionId = session.sessionId,
                    targetDeviceId = ownerDeviceId,
                    request = FinalizeSessionRequest(
                        hashAlgorithm = HASH_ALGORITHM,
                        hashHex = ciphertextChecksum,
                    ),
                )
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "finalize failed for blobKey=$blobKey" }
                // 422 most likely → cipher byte mismatch. Surface a distinct result so the UI
                // can offer "retry from scratch" rather than just a generic retry.
                val msg = e.message ?: ""
                return if (msg.contains("422")) Result.ChecksumRejectedByServer(msg)
                else Result.HttpError(e)
            }

            log(TAG, INFO) {
                "Uploaded blobKey=$blobKey serverBlobId=${finalized.blobId} (${finalized.sizeBytes}B)"
            }
            return Result.Ok(
                serverBlobId = finalized.blobId,
                plaintextChecksumHex = plaintextChecksum,
                ciphertextSizeBytes = ciphertextSize,
            )
        } finally {
            runCatching { Files.deleteIfExists(ciphertextFile) }
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

    companion object {
        private const val CHUNK_SIZE = 1 * 1024 * 1024 // 1 MiB — server's per-PATCH cap
        private const val MAX_RETRIES_PER_CHUNK = 4
        private const val HASH_ALGORITHM = "SHA-256"
    }
}

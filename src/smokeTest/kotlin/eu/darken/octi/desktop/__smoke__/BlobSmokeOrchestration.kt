package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.encryption.StreamingPayloadCipher
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.octiserver.dto.CreateSessionRequest
import eu.darken.octi.desktop.protocol.octiserver.dto.FinalizeSessionRequest
import eu.darken.octi.desktop.protocol.octiserver.dto.ModuleCommitRequest
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.protocol.sync.RemoteBlobRef
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private const val CHUNK_SIZE = 1 * 1024 * 1024
private const val HASH_ALGORITHM = "sha256"

data class UploadedSmokeBlob(
    val serverBlobId: String,
    val blobKey: String,
    val plaintextChecksumHex: String,
)

suspend fun generatePlaintext(sizeBytes: Long): Path = withContext(Dispatchers.IO) {
    val file = Files.createTempFile("octi-smoke-plain-", ".bin")
    val rng = Random(0xC0FFEE)
    Files.newOutputStream(file).use { out ->
        val buffer = ByteArray(64 * 1024)
        var remaining = sizeBytes
        while (remaining > 0) {
            rng.nextBytes(buffer)
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            out.write(buffer, 0, count)
            remaining -= count
        }
    }
    file
}

suspend fun uploadAndCommitBlob(
    account: SmokeFixture.SmokeAccount,
    ownerDeviceId: DeviceId,
    sourceFile: Path,
    blobKey: String = "smoke-blob-${UUID.randomUUID()}",
): UploadedSmokeBlob = withContext(Dispatchers.IO) {
    val cipher = StreamingPayloadCipher(account.credentials.encryptionKeyset)
    val aad = cipher.aadFor(ownerDeviceId.id, ModuleIds.FILES.id, blobKey)
    val ciphertextFile = Files.createTempFile("octi-smoke-cipher-", ".bin")
    try {
        Files.newInputStream(sourceFile).source().use { source ->
            Files.newOutputStream(ciphertextFile).sink().use { sink ->
                cipher.encrypt(source, sink, aad)
            }
        }
        val plaintextChecksumHex = sha256Hex(sourceFile)
        val ciphertextChecksumHex = sha256Hex(ciphertextFile)
        val ciphertextSize = Files.size(ciphertextFile)

        val session = account.client.createBlobSession(
            moduleId = ModuleIds.FILES,
            targetDeviceId = ownerDeviceId,
            request = CreateSessionRequest(
                sizeBytes = ciphertextSize,
                hashAlgorithm = HASH_ALGORITHM,
                hashHex = ciphertextChecksumHex,
            ),
        )

        var offset = session.offsetBytes
        RandomAccessFile(ciphertextFile.toFile(), "r").use { raf ->
            while (offset < ciphertextSize) {
                val chunkSize = CHUNK_SIZE.toLong().coerceAtMost(ciphertextSize - offset).toInt()
                val chunk = ByteArray(chunkSize)
                raf.seek(offset)
                raf.readFully(chunk)
                account.client.appendBlobChunk(
                    moduleId = ModuleIds.FILES,
                    sessionId = session.sessionId,
                    targetDeviceId = ownerDeviceId,
                    offset = offset,
                    chunk = chunk,
                )
                offset += chunkSize
            }
        }

        val finalized = account.client.finalizeBlobSession(
            moduleId = ModuleIds.FILES,
            sessionId = session.sessionId,
            targetDeviceId = ownerDeviceId,
            request = FinalizeSessionRequest(
                hashAlgorithm = HASH_ALGORITHM,
                hashHex = ciphertextChecksumHex,
            ),
        )
        commitFilesDocument(
            account = account,
            ownerDeviceId = ownerDeviceId,
            blobKey = blobKey,
            serverBlobId = finalized.blobId,
            plaintextSize = Files.size(sourceFile),
            plaintextChecksumHex = plaintextChecksumHex,
        )
        UploadedSmokeBlob(
            serverBlobId = finalized.blobId,
            blobKey = blobKey,
            plaintextChecksumHex = plaintextChecksumHex,
        )
    } finally {
        runCatching { Files.deleteIfExists(ciphertextFile) }
    }
}

suspend fun downloadAndVerifyBlob(
    reader: SmokeFixture.SmokeAccount,
    ownerDeviceId: DeviceId,
    uploaded: UploadedSmokeBlob,
    expectedFile: Path,
): Path = withContext(Dispatchers.IO) {
    val cipher = StreamingPayloadCipher(reader.credentials.encryptionKeyset)
    val aad = cipher.aadFor(ownerDeviceId.id, ModuleIds.FILES.id, uploaded.blobKey)
    val ciphertextBytes = reader.client.getBlobBytes(
        moduleId = ModuleIds.FILES,
        blobId = uploaded.serverBlobId,
        targetDeviceId = ownerDeviceId,
    )
    val destination = Files.createTempFile("octi-smoke-downloaded-", ".bin")
    try {
        Files.newOutputStream(destination).sink().buffer().use { sink ->
            cipher.decrypt(Buffer().write(ciphertextBytes), sink, aad)
        }
        sha256Hex(destination) shouldBe uploaded.plaintextChecksumHex
        Files.size(destination) shouldBe Files.size(expectedFile)
        Files.mismatch(expectedFile, destination) shouldBe -1L
        destination
    } catch (e: Throwable) {
        runCatching { Files.deleteIfExists(destination) }
        throw e
    }
}

suspend fun deleteBlobBestEffort(
    account: SmokeFixture.SmokeAccount,
    ownerDeviceId: DeviceId,
    serverBlobId: String?,
) {
    if (serverBlobId == null) return
    runCatching {
        account.client.deleteBlob(
            moduleId = ModuleIds.FILES,
            blobId = serverBlobId,
            targetDeviceId = ownerDeviceId,
            ifMatch = "*",
        )
    }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun commitFilesDocument(
    account: SmokeFixture.SmokeAccount,
    ownerDeviceId: DeviceId,
    blobKey: String,
    serverBlobId: String,
    plaintextSize: Long,
    plaintextChecksumHex: String,
) {
    val connectorId = ConnectorId(
        type = ConnectorType.OCTISERVER,
        subtype = account.credentials.serverAdress.domain,
        account = account.credentials.accountId.id,
    ).idString
    val info = FileShareInfo(
        files = listOf(
            FileShareInfo.SharedFile(
                name = "smoke.bin",
                mimeType = "application/octet-stream",
                size = plaintextSize,
                blobKey = blobKey,
                checksum = plaintextChecksumHex,
                sharedAt = Clock.System.now(),
                expiresAt = Clock.System.now() + 1.days,
                availableOn = setOf(connectorId),
                connectorRefs = mapOf(connectorId to RemoteBlobRef(serverBlobId)),
            ),
        ),
    )
    val plaintext = Serialization.json.encodeToString(FileShareInfo.serializer(), info)
    val document = encryptModulePayload(
        credentials = account.credentials.encryptionKeyset,
        ownerDeviceId = ownerDeviceId.id,
        moduleId = ModuleIds.FILES,
        plaintextJson = plaintext,
    )
    account.client.commitModule(
        moduleId = ModuleIds.FILES,
        request = ModuleCommitRequest(
            documentBase64 = Base64.Default.encode(document),
            blobRefs = listOf(ModuleCommitRequest.BlobRef(blobId = serverBlobId)),
        ),
        ifNoneMatch = "*",
    )
}

fun sha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).source().buffer().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer, 0, buffer.size)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

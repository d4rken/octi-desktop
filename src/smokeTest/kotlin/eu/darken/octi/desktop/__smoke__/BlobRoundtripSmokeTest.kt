package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.protocol.module.ModuleIds
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files

class BlobRoundtripSmokeTest {

    @Test
    fun `blob session upload commit list download via real sync-server`() = smokeTest {
        SmokeFixture.withFreshAccount { account ->
            val sourceFile = generatePlaintext(2L * 1024 * 1024 + 512 * 1024)
            var uploaded: UploadedSmokeBlob? = null
            var downloaded: java.nio.file.Path? = null
            try {
                uploaded = uploadAndCommitBlob(
                    account = account,
                    ownerDeviceId = account.deviceId,
                    sourceFile = sourceFile,
                )
                val listedBlobIds = account.client.listBlobs(ModuleIds.FILES, account.deviceId)
                    .blobs
                    .map { it.blobId }
                listedBlobIds shouldContain uploaded.serverBlobId
                downloaded = downloadAndVerifyBlob(
                    reader = account,
                    ownerDeviceId = account.deviceId,
                    uploaded = uploaded,
                    expectedFile = sourceFile,
                )
            } finally {
                deleteBlobBestEffort(account, account.deviceId, uploaded?.serverBlobId)
                runCatching { Files.deleteIfExists(sourceFile) }
                runCatching { downloaded?.let { Files.deleteIfExists(it) } }
            }
        }
    }
}

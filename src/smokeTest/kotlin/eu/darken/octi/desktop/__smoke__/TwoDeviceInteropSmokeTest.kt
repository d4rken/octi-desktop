package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.protocol.module.ModuleIds
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TwoDeviceInteropSmokeTest {

    @Test
    fun `linked devices read each other's encrypted meta`() = smokeTest {
        SmokeFixture.withLinkedDevices { deviceA, deviceB ->
            val deviceIds = deviceA.client.getDeviceList().devices.map { it.id }
            deviceIds shouldContainAll listOf(deviceA.deviceId.id, deviceB.deviceId.id)

            val plaintextFromA = """{"deviceLabel":"smoke-a","note":"hello from A"}"""
            deviceA.client.writeModule(
                moduleId = ModuleIds.META,
                payload = encryptModulePayload(
                    credentials = deviceA.credentials.encryptionKeyset,
                    ownerDeviceId = deviceA.deviceId.id,
                    moduleId = ModuleIds.META,
                    plaintextJson = plaintextFromA,
                ),
            )
            decryptModulePayload(
                credentials = deviceB.credentials.encryptionKeyset,
                ownerDeviceId = deviceA.deviceId.id,
                moduleId = ModuleIds.META,
                ciphertext = deviceB.client.readModule(ModuleIds.META, deviceA.deviceId).shouldBeOkPayload(),
            ) shouldBe plaintextFromA

            val plaintextFromB = """{"deviceLabel":"smoke-b","note":"hello from B"}"""
            deviceB.client.writeModule(
                moduleId = ModuleIds.META,
                payload = encryptModulePayload(
                    credentials = deviceB.credentials.encryptionKeyset,
                    ownerDeviceId = deviceB.deviceId.id,
                    moduleId = ModuleIds.META,
                    plaintextJson = plaintextFromB,
                ),
            )
            decryptModulePayload(
                credentials = deviceA.credentials.encryptionKeyset,
                ownerDeviceId = deviceB.deviceId.id,
                moduleId = ModuleIds.META,
                ciphertext = deviceA.client.readModule(ModuleIds.META, deviceB.deviceId).shouldBeOkPayload(),
            ) shouldBe plaintextFromB
        }
    }

    @Test
    fun `linked device B uploads blob and A downloads it`() = smokeTest {
        SmokeFixture.withLinkedDevices { deviceA, deviceB ->
            val sourceFile = generatePlaintext(1L * 1024 * 1024 + 64 * 1024)
            var uploaded: UploadedSmokeBlob? = null
            var downloaded: java.nio.file.Path? = null
            try {
                uploaded = uploadAndCommitBlob(
                    account = deviceB,
                    ownerDeviceId = deviceB.deviceId,
                    sourceFile = sourceFile,
                )
                downloaded = downloadAndVerifyBlob(
                    reader = deviceA,
                    ownerDeviceId = deviceB.deviceId,
                    uploaded = uploaded,
                    expectedFile = sourceFile,
                )
            } finally {
                deleteBlobBestEffort(deviceB, deviceB.deviceId, uploaded?.serverBlobId)
                runCatching { Files.deleteIfExists(sourceFile) }
                runCatching { downloaded?.let { Files.deleteIfExists(it) } }
            }
        }
    }
}

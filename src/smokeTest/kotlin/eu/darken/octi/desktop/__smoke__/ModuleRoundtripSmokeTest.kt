package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.modules.meta.DeviceCapabilitiesProvider
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModuleRoundtripSmokeTest {

    @Test
    fun `meta module roundtrip via real sync-server`() = smokeTest {
        SmokeFixture.withFreshAccount { account ->
            val devices = account.client.getDeviceList().devices
            devices.size shouldBe 1
            val self = devices.single()
            self.id shouldBe account.deviceId.id
            self.platform shouldBe "desktop-linux"
            self.label shouldBe "smoke"
            self.capabilities.shouldNotBeNull().shouldContainAll(DeviceCapabilitiesProvider.current())

            val plaintext = """{"deviceLabel":"smoke","smoke":true}"""
            account.client.writeModule(
                moduleId = ModuleIds.META,
                payload = encryptModulePayload(
                    credentials = account.credentials.encryptionKeyset,
                    ownerDeviceId = account.deviceId.id,
                    moduleId = ModuleIds.META,
                    plaintextJson = plaintext,
                ),
            )

            val read = account.client.readModule(ModuleIds.META, account.deviceId)
            read.shouldBeOkPayload().let { ciphertext ->
                decryptModulePayload(
                    credentials = account.credentials.encryptionKeyset,
                    ownerDeviceId = account.deviceId.id,
                    moduleId = ModuleIds.META,
                    ciphertext = ciphertext,
                ) shouldBe plaintext
            }
        }
    }
}

fun OctiServerHttpClient.ModuleReadResult.shouldBeOkPayload(): ByteArray = when (this) {
    is OctiServerHttpClient.ModuleReadResult.Ok -> payload
    OctiServerHttpClient.ModuleReadResult.NotFound -> error("Expected module payload, got NotFound")
}

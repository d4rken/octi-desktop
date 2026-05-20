package eu.darken.octi.desktop.protocol.octiserver

import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector.Companion.toConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OctiServerConnectorTest {

    @Test
    fun `toConnectorId mirrors android subtype=domain account=accountId`() {
        val credentials = OctiServer.Credentials(
            serverAdress = OctiServer.Address(domain = "prod.kserver.octi.darken.eu"),
            accountId = OctiServer.Credentials.AccountId(id = "11111111-2222-3333-4444-555555555555"),
            devicePassword = OctiServer.Credentials.DevicePassword(password = "pw"),
            encryptionKeyset = PayloadEncryption().exportKeyset(),
        )
        val id = credentials.toConnectorId()
        id.type shouldBe ConnectorType.OCTISERVER
        id.subtype shouldBe "prod.kserver.octi.darken.eu"
        id.account shouldBe "11111111-2222-3333-4444-555555555555"
        // idString is the opaque key used by Settings.connectors and DeviceListCache.perConnector.
        // Lock the exact shape so a refactor of ConnectorId.idString shape is visible in this test.
        id.idString shouldBe "kserver-prod.kserver.octi.darken.eu-11111111-2222-3333-4444-555555555555"
    }
}

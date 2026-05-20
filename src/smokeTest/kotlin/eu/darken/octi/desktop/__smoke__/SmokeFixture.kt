package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.modules.meta.DeviceCapabilitiesProvider
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.sync.CapabilitiesCodec
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.util.UUID

object SmokeFixture {

    private val serverUrl: String? = System.getProperty("smoke.server.url")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.removeSuffix("/")

    fun assumeServerConfigured() {
        Assumptions.assumeTrue(serverUrl != null) {
            "SMOKE_SERVER_URL is not set — skipping E2E smoke. Set it to e.g. http://127.0.0.1:18080"
        }
    }

    suspend fun <T> withFreshAccount(
        label: String = "smoke",
        block: suspend (SmokeAccount) -> T,
    ): T {
        assumeServerConfigured()
        val account = newAccount(label = label)
        return try {
            block(account)
        } finally {
            runCatching { account.client.deleteAccount() }
            account.close()
        }
    }

    suspend fun <T> withLinkedDevices(
        block: suspend (deviceA: SmokeAccount, deviceB: SmokeAccount) -> T,
    ): T {
        assumeServerConfigured()
        val deviceA = newAccount(label = "smoke-a")
        val deviceB = try {
            val shareCode = deviceA.client.createShareCode().shareCode
            newAccount(
                label = "smoke-b",
                shareCode = shareCode,
                keyset = deviceA.credentials.encryptionKeyset,
            )
        } catch (e: Throwable) {
            runCatching { deviceA.client.deleteAccount() }
            deviceA.close()
            throw e
        }

        return try {
            block(deviceA, deviceB)
        } finally {
            deviceB.close()
            runCatching { deviceA.client.deleteAccount() }
            deviceA.close()
        }
    }

    private suspend fun newAccount(
        label: String,
        shareCode: String? = null,
        keyset: PayloadEncryption.KeySet = PayloadEncryption().exportKeyset(),
    ): SmokeAccount {
        val address = OctiServer.Address.tryParse(serverUrl!!).getOrThrow()
        val deviceId = DeviceId(UUID.randomUUID().toString())
        val metadata = DeviceMetadata(
            version = "octi-desktop/smoke",
            platform = "desktop-linux",
            label = label,
            capabilities = CapabilitiesCodec().encodeToHeader(DeviceCapabilitiesProvider.current()),
        )

        val registrationClient = OctiServerHttpClient(
            address = address,
            deviceId = deviceId,
            deviceMetadata = metadata,
            credentials = null,
        )
        val registered = try {
            registrationClient.register(shareCode = shareCode)
        } finally {
            registrationClient.close()
        }

        val credentials = OctiServer.Credentials(
            serverAdress = address,
            accountId = OctiServer.Credentials.AccountId(registered.accountID),
            devicePassword = OctiServer.Credentials.DevicePassword(registered.password),
            encryptionKeyset = keyset,
        )
        val client = OctiServerHttpClient(
            address = address,
            deviceId = deviceId,
            deviceMetadata = metadata,
            credentials = credentials,
        )
        return SmokeAccount(client, credentials, deviceId)
    }

    data class SmokeAccount(
        val client: OctiServerHttpClient,
        val credentials: OctiServer.Credentials,
        val deviceId: DeviceId,
    ) : AutoCloseable {
        override fun close() {
            runCatching { client.close() }
        }
    }
}

fun smokeTest(block: suspend () -> Unit) = runBlocking { block() }

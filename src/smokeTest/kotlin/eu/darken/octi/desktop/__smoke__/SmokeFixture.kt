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

    private val serverUrlB: String? = System.getProperty("smoke.server.url.b")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.removeSuffix("/")

    fun assumeServerConfigured() {
        Assumptions.assumeTrue(serverUrl != null) {
            "SMOKE_SERVER_URL is not set — skipping E2E smoke. Set it to e.g. http://127.0.0.1:18080"
        }
    }

    /**
     * Skip the test cleanly if a second sync-server isn't configured. Local devs typically
     * only run one container; CI's code-checks workflow brings up both.
     */
    fun assumeTwoServersConfigured() {
        assumeServerConfigured()
        Assumptions.assumeTrue(serverUrlB != null) {
            "SMOKE_SERVER_URL_B is not set — skipping multi-connector smoke. Set it to e.g. http://127.0.0.1:18081"
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

    /**
     * Stand up one account on each of two sync-server instances, sharing the same desktop
     * deviceId. Mirrors the runtime case where a single desktop install is linked to two
     * OctiServer accounts on different servers (e.g. work + personal) — each server sees the
     * device through its own account view; the desktop merges across both. Skips cleanly when
     * `SMOKE_SERVER_URL_B` isn't set.
     */
    suspend fun <T> withTwoServerAccounts(
        block: suspend (accountA: SmokeAccount, accountB: SmokeAccount) -> T,
    ): T {
        assumeTwoServersConfigured()
        val sharedDeviceId = DeviceId(UUID.randomUUID().toString())
        val accountA = newAccount(label = "smoke-server-a", deviceIdOverride = sharedDeviceId, serverUrlOverride = serverUrl)
        val accountB = try {
            newAccount(label = "smoke-server-b", deviceIdOverride = sharedDeviceId, serverUrlOverride = serverUrlB)
        } catch (e: Throwable) {
            runCatching { accountA.client.deleteAccount() }
            accountA.close()
            throw e
        }
        return try {
            block(accountA, accountB)
        } finally {
            runCatching { accountB.client.deleteAccount() }
            accountB.close()
            runCatching { accountA.client.deleteAccount() }
            accountA.close()
        }
    }

    private suspend fun newAccount(
        label: String,
        shareCode: String? = null,
        keyset: PayloadEncryption.KeySet = PayloadEncryption().exportKeyset(),
        deviceIdOverride: DeviceId? = null,
        serverUrlOverride: String? = null,
    ): SmokeAccount {
        val address = OctiServer.Address.tryParse(serverUrlOverride ?: serverUrl!!).getOrThrow()
        val deviceId = deviceIdOverride ?: DeviceId(UUID.randomUUID().toString())
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

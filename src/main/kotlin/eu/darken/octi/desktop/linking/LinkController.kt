package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.common.log.Logging.Priority.ERROR
import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.protocol.encryption.CryptoBootstrap
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.LinkingData
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector.Companion.toConnectorId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpException
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.storage.ConnectorConfig
import eu.darken.octi.desktop.storage.Settings
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

private val TAG = logTag("Link", "Controller")

/**
 * Drives the staged link / create-account flows from input to persisted credentials.
 *
 * Both flows share the contract: every local validation runs **before** the server consume call,
 * and any failure past the server consume immediately rolls back on the server while credentials
 * are still in memory. The rollback target differs by flow:
 *  - [link] consumes a share code and registers a device; rollback = `DELETE /v1/devices/{self}`.
 *  - [createAccount] creates a brand-new account; rollback = `DELETE /v1/account`.
 *
 * Two-store commit: a successful link must end with BOTH a keystore entry AND a matching
 * `SettingsData.connectors[idString]` entry. Keystore-first (so a credential is never half-saved
 * to a discovery index pointing at nothing), then settings; on settings failure we best-effort
 * clear the keystore entry and roll back on the server (see [LinkResult.SettingsPersistFailedRolledBack]).
 *
 * [DeviceMetadata] is rebuilt at submit time (via [deviceMetadataProvider]) so user edits to
 * the device label on the Linking screen are honored on the very next attempt.
 *
 * The [gcmSivAvailable] flag drives the SIV-fallback decision for `createAccount`'s fresh
 * keyset generation — wired via [CryptoBootstrap]'s runtime probe in production; tests can
 * pin it.
 *
 * All `Throwable` catches explicitly rethrow [CancellationException] — coroutine cancellation
 * must propagate, not be reported as a `NetworkError`.
 */
class LinkController(
    private val deviceMetadataProvider: () -> DeviceMetadata,
    private val credentialsStore: CredentialsStore,
    private val settings: Settings,
    private val httpClientFactory: HttpClientFactory = DefaultHttpClientFactory,
    private val gcmSivAvailable: () -> Boolean = { CryptoBootstrap.gcmSivAvailable },
) {

    /**
     * Validate the supplied [encoded] share-code string and, if everything passes, register
     * this device against the existing account on the server. Caller supplies a fresh local
     * [deviceId].
     */
    suspend fun link(encoded: String, deviceId: DeviceId): LinkResult {
        // Stage 1: decode + parse + validate. Pure-local — never touches the network.
        val linkingData = when (val decoded = decode(encoded)) {
            is DecodeOutcome.Ok -> decoded.value
            is DecodeOutcome.Fail -> return decoded.result
        }

        // Stage 2: probe the keyset. If Tink can't parse it, the share code is unusable. We
        // fail here rather than after consuming the share code.
        val keysetCheck = runCatching { PayloadEncryption(keySet = linkingData.encryptionKeyset).exportKeyset() }
        keysetCheck.exceptionOrNull()?.let { keysetCause ->
            if (keysetCause is CancellationException) throw keysetCause
            return LinkResult.InvalidKeyset(keysetCause)
        }

        val deviceMetadata = deviceMetadataProvider()

        // The unauthenticated client is used for both the register call and (potentially) needs
        // to be closed in every exit path. A single try/finally around the whole staged flow
        // guarantees that — including the case where credentialsStore.save() succeeds but
        // client.close() itself throws (which previously was misclassified as a save failure).
        var unauthedClient: OctiServerHttpClient? = null
        try {
            // Stage 3: server consume. Beyond this point, server state has changed.
            val newCredentials = try {
                unauthedClient = httpClientFactory.create(
                    address = linkingData.serverAdress,
                    deviceId = deviceId,
                    deviceMetadata = deviceMetadata,
                    credentials = null,
                )
                val response = unauthedClient.register(shareCode = linkingData.linkCode.code)
                OctiServer.Credentials(
                    serverAdress = linkingData.serverAdress,
                    accountId = OctiServer.Credentials.AccountId(response.accountID),
                    devicePassword = OctiServer.Credentials.DevicePassword(response.password),
                    encryptionKeyset = linkingData.encryptionKeyset,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                return when {
                    cause is OctiServerHttpException && cause.status == HttpStatusCode.NotFound ->
                        LinkResult.ShareCodeExpiredOrConsumed
                    cause is OctiServerHttpException && cause.status == HttpStatusCode.Unauthorized ->
                        LinkResult.ShareCodeExpiredOrConsumed
                    else -> LinkResult.NetworkError(cause)
                }
            }

            val connectorId = newCredentials.toConnectorId()

            // Stage 4a: keystore. Keystore-first so a discovery entry never points at nothing.
            try {
                credentialsStore.save(newCredentials)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (keystoreCause: Throwable) {
                log(TAG, ERROR, keystoreCause) { "Keystore write failed; rolling back server registration" }
                return rollbackLinkRegistration(
                    address = linkingData.serverAdress,
                    deviceId = deviceId,
                    deviceMetadata = deviceMetadata,
                    credentials = newCredentials,
                    keystoreCause = keystoreCause,
                )
            }

            // Stage 4b: discovery index. Keystore was the durable commit for the credential
            // bytes; this is the durable commit for "a connector exists with this id". Failure
            // here means we have an unreachable keystore blob (no idString in settings → no key
            // to query) — best-effort cleanup + server rollback.
            try {
                settings.update { current ->
                    current.copy(
                        connectors = current.connectors + (connectorId.idString to ConnectorConfig(connectorId)),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (settingsCause: Throwable) {
                log(TAG, ERROR, settingsCause) { "Settings.connectors write failed; rolling back keystore + server" }
                return rollbackSettingsForLink(
                    address = linkingData.serverAdress,
                    deviceId = deviceId,
                    deviceMetadata = deviceMetadata,
                    credentials = newCredentials,
                    connectorId = connectorId,
                    settingsCause = settingsCause,
                )
            }
            log(TAG, INFO) { "Link succeeded for connector=${connectorId.idString}" }
            return LinkResult.Success(connectorId, newCredentials)
        } finally {
            // A throwing close() must NOT propagate over a `return Success` (or any other
            // return) — otherwise a healthy save + a flaky transport teardown turns into a
            // bogus NetworkError. Log and swallow.
            runCatching { unauthedClient?.close() }
                .onFailure { log(TAG, WARN, it) { "unauthedClient.close() failed; swallowing to preserve return value" } }
        }
    }

    private suspend fun rollbackLinkRegistration(
        address: OctiServer.Address,
        deviceId: DeviceId,
        deviceMetadata: DeviceMetadata,
        credentials: OctiServer.Credentials,
        keystoreCause: Throwable,
    ): LinkResult {
        val authedClient = httpClientFactory.create(
            address = address,
            deviceId = deviceId,
            deviceMetadata = deviceMetadata,
            credentials = credentials,
        )
        return try {
            authedClient.deleteDevice(deviceId)
            log(TAG, WARN) { "Rollback DELETE succeeded; device unregistered" }
            LinkResult.KeystoreFailureRolledBack(keystoreCause)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (rollbackCause: Throwable) {
            log(TAG, ERROR, rollbackCause) { "Rollback DELETE failed; device is orphaned on server" }
            LinkResult.OrphanedDevice(keystoreCause, rollbackCause)
        } finally {
            runCatching { authedClient.close() }
                .onFailure { log(TAG, WARN, it) { "authedClient.close() failed; swallowing to preserve return value" } }
        }
    }

    private suspend fun rollbackSettingsForLink(
        address: OctiServer.Address,
        deviceId: DeviceId,
        deviceMetadata: DeviceMetadata,
        credentials: OctiServer.Credentials,
        connectorId: ConnectorId,
        settingsCause: Throwable,
    ): LinkResult {
        val keystoreCleanup = runCatching { credentialsStore.clear(connectorId) }
        keystoreCleanup.onFailure {
            log(TAG, WARN, it) { "Best-effort keystore cleanup failed; orphan blob remains unreachable" }
        }
        val authedClient = httpClientFactory.create(
            address = address,
            deviceId = deviceId,
            deviceMetadata = deviceMetadata,
            credentials = credentials,
        )
        return try {
            authedClient.deleteDevice(deviceId)
            log(TAG, WARN) { "Settings rollback: server DELETE /v1/devices/{self} succeeded" }
            LinkResult.SettingsPersistFailedRolledBack(
                cause = settingsCause,
                keystoreCleanupFailure = keystoreCleanup.exceptionOrNull(),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (rollbackCause: Throwable) {
            log(TAG, ERROR, rollbackCause) { "Settings rollback DELETE failed; device is orphaned on server" }
            LinkResult.OrphanedDevice(settingsCause, rollbackCause)
        } finally {
            runCatching { authedClient.close() }
                .onFailure { log(TAG, WARN, it) { "authedClient.close() failed; swallowing to preserve return value" } }
        }
    }

    /**
     * Create a brand-new account on [address] and persist credentials locally. Generates a fresh
     * Tink keyset (GCM-SIV when the platform supports it; legacy SIV fallback otherwise — file
     * sharing will be disabled in that mode, surfaced separately in Settings).
     *
     * Caller is responsible for validating [address] beforehand (e.g. via [OctiServer.Address.tryParse]).
     */
    suspend fun createAccount(deviceId: DeviceId, address: OctiServer.Address): CreateAccountResult {
        val useLegacy = !gcmSivAvailable()
        if (useLegacy) {
            log(TAG, WARN) { "GCM-SIV unavailable; new account will use legacy AES256_SIV (no file sharing)" }
        }
        val freshKeyset = PayloadEncryption(useLegacyEncryption = useLegacy).exportKeyset()
        val deviceMetadata = deviceMetadataProvider()

        var unauthedClient: OctiServerHttpClient? = null
        try {
            val newCredentials = try {
                unauthedClient = httpClientFactory.create(
                    address = address,
                    deviceId = deviceId,
                    deviceMetadata = deviceMetadata,
                    credentials = null,
                )
                val response = unauthedClient.register(shareCode = null)
                OctiServer.Credentials(
                    serverAdress = address,
                    accountId = OctiServer.Credentials.AccountId(response.accountID),
                    devicePassword = OctiServer.Credentials.DevicePassword(response.password),
                    encryptionKeyset = freshKeyset,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                return when {
                    cause is OctiServerHttpException && cause.status == HttpStatusCode.BadRequest ->
                        CreateAccountResult.DeviceAlreadyRegistered
                    else -> CreateAccountResult.NetworkError(cause)
                }
            }

            val connectorId = newCredentials.toConnectorId()

            try {
                credentialsStore.save(newCredentials)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (keystoreCause: Throwable) {
                log(TAG, ERROR, keystoreCause) { "Keystore write failed; rolling back fresh account" }
                return rollbackFreshAccount(
                    address = address,
                    deviceId = deviceId,
                    deviceMetadata = deviceMetadata,
                    credentials = newCredentials,
                    keystoreCause = keystoreCause,
                )
            }

            try {
                settings.update { current ->
                    current.copy(
                        connectors = current.connectors + (connectorId.idString to ConnectorConfig(connectorId)),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (settingsCause: Throwable) {
                log(TAG, ERROR, settingsCause) { "Settings.connectors write failed; rolling back keystore + account" }
                return rollbackSettingsForCreate(
                    address = address,
                    deviceId = deviceId,
                    deviceMetadata = deviceMetadata,
                    credentials = newCredentials,
                    connectorId = connectorId,
                    settingsCause = settingsCause,
                )
            }
            log(TAG, INFO) { "Account created for connector=${connectorId.idString} on ${address.address}" }
            return CreateAccountResult.Success(connectorId, newCredentials)
        } finally {
            // A throwing close() must NOT propagate over a `return Success` (or any other
            // return) — otherwise a healthy save + a flaky transport teardown turns into a
            // bogus NetworkError. Log and swallow.
            runCatching { unauthedClient?.close() }
                .onFailure { log(TAG, WARN, it) { "unauthedClient.close() failed; swallowing to preserve return value" } }
        }
    }

    private suspend fun rollbackFreshAccount(
        address: OctiServer.Address,
        deviceId: DeviceId,
        deviceMetadata: DeviceMetadata,
        credentials: OctiServer.Credentials,
        keystoreCause: Throwable,
    ): CreateAccountResult {
        val authedClient = httpClientFactory.create(
            address = address,
            deviceId = deviceId,
            deviceMetadata = deviceMetadata,
            credentials = credentials,
        )
        return try {
            authedClient.deleteAccount()
            log(TAG, WARN) { "Rollback DELETE /v1/account succeeded; account removed" }
            CreateAccountResult.KeystoreFailureRolledBack(keystoreCause)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (rollbackCause: Throwable) {
            log(TAG, ERROR, rollbackCause) { "Rollback DELETE /v1/account failed; account is orphaned on server" }
            CreateAccountResult.OrphanedAccount(keystoreCause, rollbackCause)
        } finally {
            runCatching { authedClient.close() }
                .onFailure { log(TAG, WARN, it) { "authedClient.close() failed; swallowing to preserve return value" } }
        }
    }

    private suspend fun rollbackSettingsForCreate(
        address: OctiServer.Address,
        deviceId: DeviceId,
        deviceMetadata: DeviceMetadata,
        credentials: OctiServer.Credentials,
        connectorId: ConnectorId,
        settingsCause: Throwable,
    ): CreateAccountResult {
        val keystoreCleanup = runCatching { credentialsStore.clear(connectorId) }
        keystoreCleanup.onFailure {
            log(TAG, WARN, it) { "Best-effort keystore cleanup failed; orphan blob remains unreachable" }
        }
        val authedClient = httpClientFactory.create(
            address = address,
            deviceId = deviceId,
            deviceMetadata = deviceMetadata,
            credentials = credentials,
        )
        return try {
            authedClient.deleteAccount()
            log(TAG, WARN) { "Settings rollback: server DELETE /v1/account succeeded" }
            CreateAccountResult.SettingsPersistFailedRolledBack(
                cause = settingsCause,
                keystoreCleanupFailure = keystoreCleanup.exceptionOrNull(),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (rollbackCause: Throwable) {
            log(TAG, ERROR, rollbackCause) { "Settings rollback DELETE /v1/account failed; account is orphaned on server" }
            CreateAccountResult.OrphanedAccount(settingsCause, rollbackCause)
        } finally {
            runCatching { authedClient.close() }
                .onFailure { log(TAG, WARN, it) { "authedClient.close() failed; swallowing to preserve return value" } }
        }
    }

    private sealed class DecodeOutcome {
        data class Ok(val value: LinkingData) : DecodeOutcome()
        data class Fail(val result: LinkResult) : DecodeOutcome()
    }

    private fun decode(encoded: String): DecodeOutcome {
        if (encoded.isBlank()) return DecodeOutcome.Fail(LinkResult.InvalidBase64)
        return try {
            DecodeOutcome.Ok(LinkingData.fromEncodedString(Serialization.json, encoded.trim()))
        } catch (e: SerializationException) {
            // MUST precede the IllegalArgumentException catch — SerializationException extends
            // IllegalArgumentException in kotlinx-serialization, so reversing the order would
            // mis-route every JSON-shape error as InvalidBase64.
            DecodeOutcome.Fail(LinkResult.InvalidJson(e))
        } catch (e: IllegalArgumentException) {
            // LinkingData.fromEncodedString throws this for "not valid base64".
            DecodeOutcome.Fail(LinkResult.InvalidBase64)
        } catch (e: java.io.IOException) {
            // Okio's gzip path throws ProtocolException / IOException for bad gzip frames.
            DecodeOutcome.Fail(LinkResult.InvalidGzip)
        } catch (e: Exception) {
            // Catch-all for unexpected decoder errors; surface as JSON shape mismatch since
            // base64/gzip stages already handled their specific exceptions.
            DecodeOutcome.Fail(LinkResult.InvalidJson(e))
        }
    }

    /** Pluggable so tests can swap in a fake. */
    fun interface HttpClientFactory {
        fun create(
            address: OctiServer.Address,
            deviceId: DeviceId,
            deviceMetadata: DeviceMetadata,
            credentials: OctiServer.Credentials?,
        ): OctiServerHttpClient
    }

    object DefaultHttpClientFactory : HttpClientFactory {
        override fun create(
            address: OctiServer.Address,
            deviceId: DeviceId,
            deviceMetadata: DeviceMetadata,
            credentials: OctiServer.Credentials?,
        ): OctiServerHttpClient = OctiServerHttpClient(
            address = address,
            deviceId = deviceId,
            deviceMetadata = deviceMetadata,
            credentials = credentials,
        )
    }
}

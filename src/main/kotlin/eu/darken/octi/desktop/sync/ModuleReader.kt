package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.collections.fromGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import okio.ByteString.Companion.toByteString

private val TAG = logTag("Sync", "ModuleReader")

/**
 * Single-shot reader for a (peer, module) pair. Fetches the encrypted payload from
 * `GET /v1/module/{moduleId}?device-id={target}`, decrypts with the active account's keyset,
 * and deserializes via the provided [KSerializer].
 *
 * Lives outside `OctiServerHttpClient` so the encryption + serialization concern is reusable
 * across the polling repos for each module type (D5 / F / G phases).
 */
class ModuleReader(private val graph: AppGraph) {

    /** Decoded result wrapper so the UI can route NotFound / Error / Ok without try/catch. */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data object NotFound : Result<Nothing>()
        data class Error(val cause: Throwable) : Result<Nothing>()
    }

    /**
     * AAD used for module-payload encryption. Matches Android's `OctiServerConnector
     * .buildAssociatedData()` exactly — `${ownerDeviceId}:${moduleId}`. Tag verification fails
     * if either side uses a different shape, so this is a wire-stable contract.
     */
    fun buildAad(ownerDeviceId: DeviceId, moduleId: ModuleId): ByteArray =
        "${ownerDeviceId.id}:${moduleId.id}".toByteArray(Charsets.UTF_8)

    suspend fun <T> read(
        moduleId: ModuleId,
        targetDeviceId: DeviceId,
        serializer: KSerializer<T>,
    ): Result<T> {
        val connector = graph.primaryConnector.value
            ?: return Result.Error(IllegalStateException("No active connector (not linked)"))
        val client = connector.client
        val credentials = connector.credentials

        return try {
            when (val response = client.readModule(moduleId, targetDeviceId)) {
                OctiServerHttpClient.ModuleReadResult.NotFound -> Result.NotFound
                is OctiServerHttpClient.ModuleReadResult.Ok -> {
                    if (response.payload.isEmpty()) {
                        // The server returns 204 No Content (with an empty body) for modules
                        // that exist in the device's slot but haven't been written yet — e.g.
                        // a desktop's own PowerInfo, which the desktop never writes. Treat as
                        // NotFound rather than feeding an empty buffer to Tink (which would
                        // fail "decryption failed" and obscure the real state).
                        return Result.NotFound
                    }
                    val crypto = PayloadEncryption(keySet = credentials.encryptionKeyset)
                    // Android's OctiServerConnector encrypts each module payload with AAD bound
                    // to `${ownerDeviceId}:${moduleId}` AND gzips before encrypting. Mirror both
                    // — empty AAD or skipping gzip causes "decryption failed" on the GCM-SIV
                    // tag check (verified against a real phone-written payload during testing).
                    val aad = buildAad(targetDeviceId, moduleId)
                    val decrypted = crypto.decrypt(response.payload.toByteString(), aad)
                    val plaintext = decrypted.fromGzip()
                    val value: T = Serialization.json.decodeFromString(serializer, plaintext.utf8())
                    Result.Ok(value)
                }
            }
        } catch (e: CancellationException) {
            // MUST rethrow before the generic Throwable branch — kotlinx.coroutines uses
            // CancellationException to tear down job hierarchies. Swallowing it here turns
            // structured cancellation into a stale Result.Error emission and breaks any caller
            // that relies on cancel-replace semantics (e.g. activeClient transitions or
            // refresh-supersedes-prior-fetch).
            throw e
        } catch (e: Throwable) {
            log(TAG, Logging.Priority.WARN, e) {
                "Read failed for module=${moduleId.logLabel} peer=${targetDeviceId.logLabel}"
            }
            Result.Error(e)
        }
    }
}


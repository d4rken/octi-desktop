package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.sync.ConnectorId

/**
 * Outcome of a [LinkController.link] call. UI maps these to user-visible error strings.
 *
 * The validation stages are surfaced separately so users can be told *where* the link code
 * failed — and so the share code isn't consumed unless all local validation passed.
 */
sealed class LinkResult {
    /**
     * Linking succeeded — credentials are in the keystore AND a [eu.darken.octi.desktop.storage.ConnectorConfig]
     * entry exists in `SettingsData.connectors`. Carries [connectorId] and the freshly-minted
     * [credentials] so AppGraph can build an [eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector]
     * without re-reading the keystore.
     */
    data class Success(
        val connectorId: ConnectorId,
        val credentials: OctiServer.Credentials,
    ) : LinkResult()

    /** Input wasn't valid base64. Local validation failure; share code untouched. */
    data object InvalidBase64 : LinkResult()

    /** Bytes decoded but couldn't be ungzipped. Local validation failure. */
    data object InvalidGzip : LinkResult()

    /** Gzipped bytes weren't valid JSON in the LinkingData schema. Local validation failure. */
    data class InvalidJson(val cause: Throwable) : LinkResult()

    /** JSON parsed but Tink rejected the keyset bytes. Local validation failure. */
    data class InvalidKeyset(val cause: Throwable) : LinkResult()

    /** Server rejected the share code as expired or already consumed. (HTTP 401/404 path.) */
    data object ShareCodeExpiredOrConsumed : LinkResult()

    /** Network or other server-side failure during the link call itself. */
    data class NetworkError(val cause: Throwable) : LinkResult()

    /**
     * Server consumed the share code and returned credentials, but persisting them to the
     * keystore failed. The link controller has already issued a rollback `DELETE
     * /v1/devices/{self}` so no orphaned device remains on the server.
     */
    data class KeystoreFailureRolledBack(val cause: Throwable) : LinkResult()

    /**
     * Keystore save succeeded but writing the discovery entry to [eu.darken.octi.desktop.storage.SettingsData.connectors]
     * failed. The controller has rolled back: keystore entry deleted (best effort) AND server-side
     * `DELETE /v1/devices/{self}` issued. Surface as a "try again" error to the user; their
     * state is consistent (no local creds, no server-side device).
     *
     * [keystoreCleanupFailure] is non-null when the best-effort keystore delete itself threw; the
     * orphan keystore entry is unreachable (no settings entry → no idString to query) so this is
     * informational, not fatal.
     */
    data class SettingsPersistFailedRolledBack(
        val cause: Throwable,
        val keystoreCleanupFailure: Throwable? = null,
    ) : LinkResult()

    /**
     * Server consumed the share code AND the rollback DELETE itself failed. The user's device
     * is now registered on the server with no local credentials. Settings UI must surface this
     * with a "remove the orphaned device manually" affordance.
     */
    data class OrphanedDevice(val keystoreCause: Throwable, val rollbackCause: Throwable) : LinkResult()
}

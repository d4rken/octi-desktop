package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.platform.DesktopIdentity
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector.Companion.toConnectorId
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.storage.keystore.Keystore

/**
 * Thin facade over the [Keystore] for credential entries. Each saved entry is keyed by the
 * full [ConnectorId.idString] (composed with the channel-aware [DesktopIdentity.credentialsKeyFor]
 * prefix), so a single channel can host multiple connector accounts without collision and
 * canary credentials never overlap with stable's.
 *
 * The keystore has no list-by-prefix operation across all backends (DPAPI is a directory,
 * libsecret supports it, the security CLI is awkward) — discovery of "which connector ids are
 * configured" lives in [eu.darken.octi.desktop.storage.SettingsData.connectors]. This class is
 * value storage only; callers always know the [ConnectorId] they want.
 *
 * JSON-encoded on disk so the format is debuggable (and so we don't burn binary-format
 * complexity for a single payload type).
 */
class CredentialsStore(
    private val keystore: Keystore,
    private val identity: DesktopIdentity = DesktopIdentity.current,
) {

    fun save(credentials: OctiServer.Credentials) {
        val connectorId = credentials.toConnectorId()
        val json = Serialization.json.encodeToString(OctiServer.Credentials.serializer(), credentials)
        keystore.store(identity.credentialsKeyFor(connectorId.idString), json.toByteArray(Charsets.UTF_8))
    }

    fun load(connectorId: ConnectorId): OctiServer.Credentials? {
        val bytes = keystore.load(identity.credentialsKeyFor(connectorId.idString)) ?: return null
        return Serialization.json.decodeFromString(
            OctiServer.Credentials.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    }

    fun clear(connectorId: ConnectorId) {
        keystore.delete(identity.credentialsKeyFor(connectorId.idString))
    }
}

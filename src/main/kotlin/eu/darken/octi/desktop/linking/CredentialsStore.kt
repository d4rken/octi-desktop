package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.platform.DesktopIdentity
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.storage.keystore.Keystore

/**
 * Thin facade over the [Keystore] for the single MVP credentials slot. Keys are JSON-encoded so
 * the on-disk format is debuggable (and so we don't burn binary-format complexity for a single
 * payload type).
 *
 * The storage key is channel-aware via [DesktopIdentity] — canary credentials never collide
 * with stable's. The MVP supports one active OctiServer account at a time; multi-account would
 * key by `accountId` instead of [storageKey], that's a follow-up.
 */
class CredentialsStore(
    private val keystore: Keystore,
    private val storageKey: String = DesktopIdentity.current.credentialsKey,
) {

    fun save(credentials: OctiServer.Credentials) {
        val json = Serialization.json.encodeToString(OctiServer.Credentials.serializer(), credentials)
        keystore.store(storageKey, json.toByteArray(Charsets.UTF_8))
    }

    fun load(): OctiServer.Credentials? {
        val bytes = keystore.load(storageKey) ?: return null
        return Serialization.json.decodeFromString(
            OctiServer.Credentials.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    }

    fun clear() {
        keystore.delete(storageKey)
    }
}

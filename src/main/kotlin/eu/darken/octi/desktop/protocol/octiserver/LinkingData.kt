package eu.darken.octi.desktop.protocol.octiserver

import eu.darken.octi.desktop.protocol.collections.fromGzip
import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * The blob a phone hands to the desktop to bootstrap a link: server address, ephemeral share
 * code (60 min server-side TTL), and the encryption keyset. Wire encoding is gzip-then-base64
 * over the JSON form.
 *
 * Wire-spelling note: Kotlin property `serverAdress` (typo) ↔ wire `"serverAddress"` (correct).
 * Different from [OctiServer.Credentials.serverAdress] where the wire ALSO has the typo. Both
 * must be preserved exactly as-is across Android/desktop.
 */
@Serializable
data class LinkingData(
    @SerialName("serverAddress") val serverAdress: OctiServer.Address,
    @SerialName("shareCode") val linkCode: OctiServer.Credentials.LinkCode,
    @SerialName("encryptionKeySet") val encryptionKeyset: PayloadEncryption.KeySet,
) {

    fun toEncodedString(json: Json): String = json.encodeToString(this)
        .toByteArray()
        .toByteString()
        .toGzip()
        .base64()

    companion object {
        fun fromEncodedString(json: Json, encoded: String): LinkingData = (encoded
            .decodeBase64() ?: throw IllegalArgumentException("Invalid link code: not valid base64"))
            .fromGzip()
            .let { json.decodeFromString<LinkingData>(it.utf8()) }
    }
}

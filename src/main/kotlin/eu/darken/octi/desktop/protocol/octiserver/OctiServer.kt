package eu.darken.octi.desktop.protocol.octiserver

import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.serialization.serializer.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * OctiServer wire types. **Wire-spelling preserved verbatim** — the Kotlin property name
 * `serverAdress` (typo) maps to the wire key `"serverAdress"` (the server also has the typo
 * here). [LinkingData] has the same Kotlin typo but its wire key is `"serverAddress"` (the
 * server fixed *that* one historically). Don't unify them.
 */
interface OctiServer {

    @Serializable
    enum class Official(val address: Address) {
        @SerialName("PROD") PROD(Address("prod.kserver.octi.darken.eu")),
        @SerialName("BETA") BETA(Address("beta.kserver.octi.darken.eu")),
        @SerialName("LOCAL") LOCAL(Address("blasphemy.greenkingdom", protocol = "http", port = 8080)),
    }

    @Serializable
    data class Address(
        @SerialName("domain") val domain: String,
        @SerialName("protocol") val protocol: String = "https",
        @SerialName("port") val port: Int = 443,
    ) {
        val address: String
            get() = "$protocol://$domain:$port"
    }

    @Serializable
    data class Credentials(
        @SerialName("serverAdress") val serverAdress: Address,
        @SerialName("accountId") val accountId: AccountId,
        @SerialName("devicePassword") val devicePassword: DevicePassword,
        @SerialName("encryptionKeyset") val encryptionKeyset: PayloadEncryption.KeySet,
        @Serializable(with = InstantSerializer::class) @SerialName("createdAt") val createdAt: Instant = Clock.System.now(),
    ) {

        override fun toString(): String =
            "OctiServer.Credentials(server=$serverAdress, account=$accountId, password=$devicePassword)"

        @Serializable
        data class AccountId(@SerialName("id") val id: String = UUID.randomUUID().toString())

        @Serializable
        data class DevicePassword(@SerialName("password") val password: String) {
            override fun toString(): String = "DevicePassword(code=${password.take(4)}...)"
        }

        @Serializable
        data class LinkCode(@SerialName("code") val code: String) {
            override fun toString(): String = "ShareCode(code=${code.take(4)}...)"
        }
    }
}

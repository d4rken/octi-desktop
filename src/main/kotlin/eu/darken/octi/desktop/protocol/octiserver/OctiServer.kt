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
    }

    @Serializable
    data class Address(
        @SerialName("domain") val domain: String,
        @SerialName("protocol") val protocol: String = "https",
        @SerialName("port") val port: Int = 443,
    ) {
        val address: String
            get() = "$protocol://$domain:$port"

        companion object {
            // IPv4 dotted-quad and DNS hostnames only. Underscore is rejected — RFC 1123 allows
            // it in some contexts but TLS / SNI implementations vary, and the simpler ruleset
            // matches what Android's AddOctiServerVM accepts.
            private val HOST_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9.\\-]*[A-Za-z0-9])?\$")

            /**
             * Parse a user-entered server URL into an [Address]. Accepts:
             *  - `host` → `https://host:443`
             *  - `host:port` → `https://host:port`
             *  - `http(s)://host[:port]`
             *
             * Trims whitespace, lowercases the scheme. Rejects everything else (paths, queries,
             * fragments, userinfo, IPv6 literals, non-http(s) schemes, out-of-range ports). On
             * rejection the failure carries an [InvalidServerAddress] with a user-displayable
             * message.
             */
            fun tryParse(raw: String): Result<Address> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return fail("Address cannot be empty")
                if ('[' in trimmed || ']' in trimmed) {
                    return fail("IPv6 literals are not supported — use IPv4 or a DNS name")
                }
                if ('@' in trimmed) return fail("Userinfo is not allowed")

                val schemeMarker = "://"
                val (scheme, rest) = if (schemeMarker in trimmed) {
                    val schemeIdx = trimmed.indexOf(schemeMarker)
                    val rawScheme = trimmed.substring(0, schemeIdx).lowercase()
                    if (rawScheme != "http" && rawScheme != "https") {
                        return fail("Scheme must be http or https")
                    }
                    rawScheme to trimmed.substring(schemeIdx + schemeMarker.length)
                } else {
                    "https" to trimmed
                }

                if ('/' in rest || '?' in rest || '#' in rest) {
                    return fail("Paths, queries, and fragments are not allowed")
                }

                val colonIdx = rest.indexOf(':')
                val (host, portStr, hasExplicitPort) = if (colonIdx >= 0) {
                    Triple(rest.substring(0, colonIdx), rest.substring(colonIdx + 1), true)
                } else {
                    Triple(rest, "", false)
                }

                if (host.isEmpty()) return fail("Host cannot be empty")
                if (!HOST_REGEX.matches(host)) return fail("Host contains invalid characters")

                val port = if (hasExplicitPort) {
                    // Treat `host:` (colon with no digits) and `host:abc` as malformed instead of
                    // silently falling back to the default port — the user clearly intended to
                    // specify one and we should surface the mistake.
                    if (portStr.isEmpty()) return fail("Port is empty after ':'")
                    val p = portStr.toIntOrNull() ?: return fail("Port must be a number")
                    if (p !in 1..65535) return fail("Port must be between 1 and 65535")
                    p
                } else {
                    if (scheme == "http") 80 else 443
                }
                return Result.success(Address(domain = host, protocol = scheme, port = port))
            }

            private fun fail(message: String): Result<Address> =
                Result.failure(InvalidServerAddress(message))
        }
    }

    /** Thrown by [Address.tryParse] failures. Message is user-displayable. */
    class InvalidServerAddress(message: String) : IllegalArgumentException(message)

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

package eu.darken.octi.desktop.platform

import eu.darken.octi.desktop.BuildConfig

/**
 * Single source of truth for channel-derived identity strings. Used by [PlatformDetector] to
 * resolve config/data paths, by `CredentialsStore` to key the OS keystore entry, and by each
 * keystore implementation to label its entries (libsecret schema service, Keychain `-s` service,
 * DPAPI directory).
 *
 * Stable and canary must NEVER share these strings — if they do, a canary install will read /
 * write stable's settings and credentials and vice versa. The sealed shape forces every channel
 * to declare all three fields together so a future channel can't sneak in with only a partial
 * override.
 *
 * Unknown channel throws at first access. We'd rather see a hard startup failure than silently
 * fall back to stable's storage and leak credentials between channels.
 */
sealed class DesktopIdentity {
    /** Lower-case, filesystem-safe app name. Used as the config / data directory leaf. */
    abstract val appName: String

    /**
     * Keystore key prefix for credentials entries. Each saved credential set is stored under
     * `"$credentialsKeyPrefix.${connectorId.idString}"`, so a single channel can host multiple
     * connector accounts without collision. Channel-aware so canary and stable never share an
     * entry — see [credentialsKeyFor].
     */
    abstract val credentialsKeyPrefix: String

    /** Keystore service label (libsecret `service` / Keychain `-s`). Not used by DPAPI. */
    abstract val keystoreServiceLabel: String

    /** Compose the full keystore key for a given connector identifier. */
    fun credentialsKeyFor(connectorIdString: String): String =
        "$credentialsKeyPrefix.$connectorIdString"

    data object Stable : DesktopIdentity() {
        override val appName = "octi"
        override val credentialsKeyPrefix = "octiserver.credentials"
        override val keystoreServiceLabel = "eu.darken.octi.desktop"
    }

    data object Canary : DesktopIdentity() {
        override val appName = "octi-canary"
        override val credentialsKeyPrefix = "octiserver.credentials.canary"
        override val keystoreServiceLabel = "eu.darken.octi.desktop.canary"
    }

    companion object {
        val current: DesktopIdentity by lazy { fromChannel(BuildConfig.CHANNEL) }

        /** Visible for tests so both branches can be exercised in a single JVM. */
        internal fun fromChannel(channel: String): DesktopIdentity = when (channel) {
            "stable" -> Stable
            "canary" -> Canary
            else -> error(
                "Unknown channel '$channel' — refusing to fall back to stable's storage. " +
                    "Add a DesktopIdentity branch for this channel before building.",
            )
        }
    }
}

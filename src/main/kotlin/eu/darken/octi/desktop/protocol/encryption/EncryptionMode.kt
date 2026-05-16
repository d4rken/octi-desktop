package eu.darken.octi.desktop.protocol.encryption

/**
 * Wire enum (via [PayloadEncryption.KeySet.type]) identifying which cipher family a keyset uses.
 * Identical to `app-main`'s `EncryptionMode` — must stay byte-stable.
 */
enum class EncryptionMode(val typeString: String) {
    /** Default for new accounts. Nonce-misuse-resistant AEAD, supports associated data. */
    AES256_GCM_SIV("AES256_GCM_SIV"),

    /** Legacy deterministic encryption. No associated data support. */
    AES256_SIV("AES256_SIV"),
    ;

    val isLegacy: Boolean get() = this == AES256_SIV

    companion object {
        fun fromTypeString(type: String?): EncryptionMode? = entries.find { it.typeString == type }
    }
}

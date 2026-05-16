package eu.darken.octi.desktop.protocol.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AesGcmSivKeyManager
import eu.darken.octi.desktop.common.log.Logging.Priority.VERBOSE
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.protocol.serialization.serializer.ByteStringSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Port of `sync-core/.../PayloadEncryption`. Tink-Java replacement for tink-android.
 *
 * Wire-compatible: keysets serialized with `TinkProtoKeysetFormat.serializeKeyset` round-trip
 * between tink-android and tink-java identically (same proto, same `InsecureSecretKeyAccess`).
 *
 * The class is identical in shape to the Android original; the only Android-specific bits
 * dropped are `Parcelable`/`TypeParceler<ByteString, ByteStringParcelizer>` on [KeySet].
 */
class PayloadEncryption(
    private val keySet: KeySet? = null,
    private val useLegacyEncryption: Boolean = false,
) {

    init {
        CryptoBootstrap.ensureInitialized()
    }

    private val keysetHandle by lazy {
        keySet?.let {
            TinkProtoKeysetFormat.parseKeyset(keySet.key.toByteArray(), InsecureSecretKeyAccess.get())
        } ?: if (useLegacyEncryption) {
            KeysetHandle.generateNew(KeyTemplates.get(EncryptionMode.AES256_SIV.typeString))
        } else {
            KeysetHandle.generateNew(AesGcmSivKeyManager.aes256GcmSivTemplate())
        }
    }

    private val isSiv: Boolean
        get() = EncryptionMode.fromTypeString(keySet?.type)?.isLegacy == true ||
            (keySet == null && useLegacyEncryption)

    private val aeadPrimitive by lazy { keysetHandle.getPrimitive(Aead::class.java) }
    private val daeadPrimitive by lazy { keysetHandle.getPrimitive(DeterministicAead::class.java) }

    fun exportKeyset(): KeySet {
        val serialized = TinkProtoKeysetFormat.serializeKeyset(keysetHandle, InsecureSecretKeyAccess.get())
        val type = keySet?.type
            ?: if (useLegacyEncryption) EncryptionMode.AES256_SIV.typeString else EncryptionMode.AES256_GCM_SIV.typeString
        return KeySet(type = type, key = serialized.toByteString())
            .also { log(TAG, VERBOSE) { "exportKeyset(): $it" } }
    }

    /**
     * @param associatedData honored only for GCM-SIV keysets. Legacy SIV ignores AD — existing
     *   ciphertext was produced without it, so changing this would break decryption.
     */
    fun encrypt(data: ByteString, associatedData: ByteArray = ByteArray(0)): ByteString = if (isSiv) {
        daeadPrimitive.encryptDeterministically(data.toByteArray(), ByteArray(0))
    } else {
        aeadPrimitive.encrypt(data.toByteArray(), associatedData)
    }.toByteString().also { log(TAG, VERBOSE) { "Encrypted ${data.size}B → ${it.size}B" } }

    /** @see encrypt for [associatedData] behavior with legacy SIV keysets. */
    fun decrypt(data: ByteString, associatedData: ByteArray = ByteArray(0)): ByteString = if (isSiv) {
        daeadPrimitive.decryptDeterministically(data.toByteArray(), ByteArray(0))
    } else {
        aeadPrimitive.decrypt(data.toByteArray(), associatedData)
    }.toByteString().also { log(TAG, VERBOSE) { "Decrypted ${data.size}B → ${it.size}B" } }

    @Serializable
    data class KeySet(
        @SerialName("type") val type: String,
        @Serializable(with = ByteStringSerializer::class) @SerialName("key") val key: ByteString,
    ) {
        override fun toString(): String = "KeySet(type=$type, key=${key.base64().take(4)}...)"
    }

    companion object {
        private val TAG = logTag("Crypto", "Payload")
    }
}

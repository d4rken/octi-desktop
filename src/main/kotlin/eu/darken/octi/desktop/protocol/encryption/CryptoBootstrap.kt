package eu.darken.octi.desktop.protocol.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmSivKeyManager
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import eu.darken.octi.desktop.common.log.Logging.Priority.ERROR
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag

/**
 * Desktop port of `sync-core/.../CryptoBootstrap`. Same Tink registry setup; simpler runtime
 * detection — the desktop branch always installs BouncyCastle since JDK 21's default JCE does
 * not include `AES/GCM-SIV/NoPadding`.
 *
 * The Android side has additional logic to feature-detect a working AES-GCM-SIV provider on
 * older API levels and to install Conscrypt; that path doesn't apply here, so it's omitted.
 *
 * Like the Android side: bootstrap fires once on first reference. Direct construction sites
 * (PayloadEncryption.init) call [ensureInitialized] up front so the registry is populated.
 */
internal object CryptoBootstrap {
    private val TAG = logTag("Crypto", "Bootstrap")

    val gcmSivAvailable: Boolean

    init {
        if (!platformHasWorkingGcmSiv()) {
            installBouncyCastle()
        }

        DeterministicAeadConfig.register()
        AeadConfig.register()
        AesGcmSivKeyManager.register(true)
        StreamingAeadConfig.register()
        log(TAG) { "DeterministicAead + Aead + AesGcmSiv + StreamingAead registered" }

        gcmSivAvailable = verifyTinkAesGcmSivWorks()
        if (gcmSivAvailable) {
            log(TAG) { "Tink AES-GCM-SIV round-trip verified" }
        } else {
            log(TAG, ERROR) { "Tink AES-GCM-SIV round-trip FAILED — desktop cannot create new GCM-SIV accounts" }
        }
    }

    /** Force class load (init block runs). Naming the side effect makes audit easier. */
    fun ensureInitialized() = Unit

    private fun platformHasWorkingGcmSiv(): Boolean = try {
        // RFC 8452 vector check — distinguishes a real AES-GCM-SIV provider from one that
        // silently substitutes AES-GCM under the same transformation name.
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM-SIV/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(ByteArray(16).also { it[0] = 1 }, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, ByteArray(12).also { it[0] = 3 }),
        )
        val tag = byteArrayOf(
            0xDC.toByte(), 0x20.toByte(), 0xE2.toByte(), 0xD8.toByte(),
            0x3F.toByte(), 0x25.toByte(), 0x70.toByte(), 0x5B.toByte(),
            0xB4.toByte(), 0x9E.toByte(), 0x43.toByte(), 0x9E.toByte(),
            0xCA.toByte(), 0x56.toByte(), 0xDE.toByte(), 0x25.toByte(),
        )
        cipher.doFinal(ByteArray(0)).contentEquals(tag)
    } catch (_: Throwable) {
        false
    }

    private fun verifyTinkAesGcmSivWorks(): Boolean = try {
        val testKeyset = KeysetHandle.generateNew(AesGcmSivKeyManager.aes256GcmSivTemplate())
        val aead = testKeyset.getPrimitive(Aead::class.java)
        val ciphertext = aead.encrypt("octi-init-probe".toByteArray(), null)
        aead.decrypt(ciphertext, null).contentEquals("octi-init-probe".toByteArray())
    } catch (_: Throwable) {
        false
    }

    private fun installBouncyCastle() {
        try {
            val bcClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            val provider = bcClass.getDeclaredConstructor().newInstance() as java.security.Provider
            java.security.Security.insertProviderAt(provider, 1)
            log(TAG) { "Installed BouncyCastle as JCE provider (priority 1)" }
        } catch (_: ClassNotFoundException) {
            log(TAG, WARN) { "BouncyCastle not on classpath — AES-GCM-SIV may be unavailable" }
        } catch (e: ReflectiveOperationException) {
            log(TAG, ERROR, e) { "Failed to instantiate BouncyCastle provider" }
        }
    }
}

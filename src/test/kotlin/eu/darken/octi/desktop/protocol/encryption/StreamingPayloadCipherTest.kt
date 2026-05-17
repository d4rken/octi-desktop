package eu.darken.octi.desktop.protocol.encryption

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Self-round-trip and parameter-rejection tests for [StreamingPayloadCipher]. Cross-platform
 * fixture tests (Android-encrypted → desktop-decrypted) are a follow-up and depend on having
 * a fixture file checked in from the Android side — see plan §"Drift Strategy" for the
 * intended CI flow.
 */
class StreamingPayloadCipherTest {

    private fun newGcmSivKeyset(): PayloadEncryption.KeySet = PayloadEncryption().exportKeyset()

    private val aad = "device-abc:module-files:blob-xyz".toByteArray(Charsets.UTF_8)

    @Test
    @DisplayName("AES256_SIV keyset is rejected at construction")
    fun rejectsLegacySiv() {
        val sivKeyset = PayloadEncryption(useLegacyEncryption = true).exportKeyset()
        sivKeyset.type shouldBe EncryptionMode.AES256_SIV.typeString
        shouldThrow<IllegalArgumentException> { StreamingPayloadCipher(sivKeyset) }
    }

    @Test
    @DisplayName("small payload round-trips")
    fun smallRoundTrip() {
        val cipher = StreamingPayloadCipher(newGcmSivKeyset())
        val plaintext = "Octi 🐙 desktop streaming probe — 0123456789".toByteArray()

        val encrypted = Buffer().also { cipher.encrypt(Buffer().write(plaintext), it, aad) }
        encrypted.size shouldNotBe plaintext.size.toLong()

        val decrypted = Buffer().also { cipher.decrypt(encrypted, it, aad) }
        decrypted.readByteArray() shouldBe plaintext
    }

    @Test
    @DisplayName("3 MiB payload round-trips across multiple 1 MiB segments")
    fun multiSegmentRoundTrip() {
        val cipher = StreamingPayloadCipher(newGcmSivKeyset())
        // 3 MiB triggers ≥3 segments — exercises the segment-boundary code paths the
        // single-segment case never touches.
        val plaintext = ByteArray(3 * 1024 * 1024) { (it and 0xFF).toByte() }

        val encrypted = Buffer().also { cipher.encrypt(Buffer().write(plaintext), it, aad) }
        val decrypted = Buffer().also { cipher.decrypt(encrypted, it, aad) }
        decrypted.readByteArray() shouldBe plaintext
    }

    @Test
    @DisplayName("ciphertextSize matches actual ciphertext bytes produced")
    fun ciphertextSizeMatches() {
        val cipher = StreamingPayloadCipher(newGcmSivKeyset())
        listOf(0L, 100L, 1024L * 1024L, 3L * 1024L * 1024L).forEach { plaintextSize ->
            val plaintext = ByteArray(plaintextSize.toInt())
            val encrypted = Buffer().also { cipher.encrypt(Buffer().write(plaintext), it, aad) }
            cipher.ciphertextSize(plaintextSize) shouldBe encrypted.size
        }
    }

    @Test
    @DisplayName("decrypting with a different AAD throws (rebinds to context)")
    fun aadMismatchFails() {
        val cipher = StreamingPayloadCipher(newGcmSivKeyset())
        val plaintext = "secret".toByteArray()
        val encrypted = Buffer().also { cipher.encrypt(Buffer().write(plaintext), it, aad) }
        val wrongAad = "device-zzz:module-files:blob-xyz".toByteArray()
        shouldThrow<Exception> {
            cipher.decrypt(encrypted, Buffer(), wrongAad)
        }
    }

    @Test
    @DisplayName("decrypting with a fresh keyset of the same mode fails (keyset isolation)")
    fun keysetIsolation() {
        val cipher1 = StreamingPayloadCipher(newGcmSivKeyset())
        val cipher2 = StreamingPayloadCipher(newGcmSivKeyset())
        val plaintext = "secret".toByteArray()
        val encrypted = Buffer().also { cipher1.encrypt(Buffer().write(plaintext), it, aad) }
        shouldThrow<Exception> {
            cipher2.decrypt(encrypted, Buffer(), aad)
        }
    }

    @Test
    @DisplayName("aadFor produces the wire-spec format `deviceId:moduleId:blobKey`")
    fun aadForFormat() {
        val cipher = StreamingPayloadCipher(newGcmSivKeyset())
        cipher.aadFor("dev-1", "mod-files", "blob-7") shouldBe "dev-1:mod-files:blob-7".toByteArray()
    }

    @Test
    @DisplayName("HKDF-SHA256 helper is deterministic for fixed inputs")
    fun hkdfDeterministic() {
        val a = StreamingPayloadCipher.hkdfSha256(
            ikm = "ikm".toByteArray(),
            salt = "salt".toByteArray(),
            info = "info".toByteArray(),
            length = 32,
        )
        val b = StreamingPayloadCipher.hkdfSha256(
            ikm = "ikm".toByteArray(),
            salt = "salt".toByteArray(),
            info = "info".toByteArray(),
            length = 32,
        )
        a.toByteString() shouldBe b.toByteString()
    }
}

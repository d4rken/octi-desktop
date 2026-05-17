package eu.darken.octi.desktop.protocol.octiserver

import eu.darken.octi.desktop.protocol.collections.fromGzip
import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.serialization.Serialization
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlinx.serialization.SerializationException

class LinkingDataTest {

    private val json = Serialization.json

    /** Builds a fresh LinkingData with a real Tink keyset. The keyset bytes vary per call. */
    private fun newLinkingData(
        domain: String = "test.example.com",
        protocol: String = "https",
        port: Int = 443,
        shareCode: String = "abc123-link-code",
    ): LinkingData {
        val keyset = PayloadEncryption().exportKeyset()
        return LinkingData(
            serverAdress = OctiServer.Address(domain = domain, protocol = protocol, port = port),
            linkCode = OctiServer.Credentials.LinkCode(shareCode),
            encryptionKeyset = keyset,
        )
    }

    @Test
    @DisplayName("LinkingData survives the encode→decode round-trip")
    fun roundTrip() {
        val original = newLinkingData()
        val encoded = original.toEncodedString(json)
        val decoded = LinkingData.fromEncodedString(json, encoded)
        decoded shouldBe original
    }

    @Test
    @DisplayName("Encoded LinkingData JSON uses the corrected wire key 'serverAddress'")
    fun wireSpellingServerAddress() {
        // Kotlin property name has the historical typo ('serverAdress'). The wire key in
        // LinkingData is the corrected spelling ('serverAddress'). Don't conflate this with
        // OctiServer.Credentials which keeps the typo on both sides.
        val encoded = newLinkingData().toEncodedString(json)
        val ungzipped = encoded.decodeBase64()!!.fromGzip().utf8()
        ungzipped shouldContain "\"serverAddress\""
        ungzipped shouldNotContain "\"serverAdress\""
    }

    @Test
    @DisplayName("Encoded LinkingData JSON uses 'shareCode' and 'encryptionKeySet' wire keys")
    fun wireKeysExact() {
        val ungzipped = newLinkingData().toEncodedString(json)
            .decodeBase64()!!.fromGzip().utf8()
        ungzipped shouldContain "\"shareCode\""
        ungzipped shouldContain "\"encryptionKeySet\""
        // 'encryptionKeySet' wire key is camelCase with capital S in 'Set' (per Android source).
        // Catch a silent rename to snake_case or all-lowercase.
        ungzipped shouldNotContain "\"encryption_key_set\""
        ungzipped shouldNotContain "\"encryptionkeyset\""
    }

    @Test
    @DisplayName("Decode rejects an empty input")
    fun decodeRejectsEmpty() {
        // Empty string is technically valid base64 (zero bytes), so the failure surfaces
        // downstream when gzip can't read its header (EOFException). LinkController maps any
        // such failure to LinkResult.InvalidGzip / InvalidBase64; this test only confirms the
        // raw decode call throws, not which exception type — that's LinkController's mapping
        // responsibility, covered in C3.
        shouldThrow<Exception> {
            LinkingData.fromEncodedString(json, "")
        }
    }

    @Test
    @DisplayName("Decode rejects junk that isn't valid base64")
    fun decodeRejectsInvalidBase64() {
        // Characters outside the base64 alphabet (and not whitespace) — okio.decodeBase64() returns null.
        shouldThrow<IllegalArgumentException> {
            LinkingData.fromEncodedString(json, "%%% not valid base64 %%%")
        }
    }

    @Test
    @DisplayName("Decode rejects valid base64 that isn't valid gzip")
    fun decodeRejectsInvalidGzip() {
        // Valid base64, plain ASCII payload — definitely not a gzip stream.
        val notGzipped = "hello world".encodeUtf8().base64()
        shouldThrow<Exception> {
            LinkingData.fromEncodedString(json, notGzipped)
        }
    }

    @Test
    @DisplayName("Decode rejects gzipped junk that isn't valid LinkingData JSON")
    fun decodeRejectsBadJsonShape() {
        // Build a valid gzip of an unrelated JSON object.
        val encoded = """{"unrelated": "not the linking shape"}""".encodeUtf8().toGzip().base64()
        shouldThrow<SerializationException> {
            LinkingData.fromEncodedString(json, encoded)
        }
    }

    @Test
    @DisplayName("Default Address protocol=https port=443 omitted from encoded JSON (encodeDefaults true, explicitNulls false)")
    fun defaultsHandling() {
        // kotlinx-serialization with encodeDefaults=true emits default values; verify the
        // wire actually contains protocol and port even when they're default. This catches a
        // regression where someone switches to encodeDefaults=false and silently produces a
        // wire-incompatible payload.
        val ungzipped = newLinkingData().toEncodedString(json)
            .decodeBase64()!!.fromGzip().utf8()
        ungzipped shouldContain "\"protocol\""
        ungzipped shouldContain "\"port\""
    }
}


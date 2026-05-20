package eu.darken.octi.desktop.protocol.encryption.interop

import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.encryption.StreamingPayloadCipher
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Always-on cross-repo wire-compatibility gate. Pins desktop's [PayloadEncryption] and
 * [StreamingPayloadCipher] against committed Android/JVM ciphertext + plaintext + AAD,
 * fetched at the SHA in `fixture-lock.json` (see [InteropFixtureSync]).
 *
 * Fails if:
 *   - The manifest sha256 disagrees with the lockfile (cache poisoning / upstream rewrite).
 *   - Any committed ciphertext fails to decrypt under the committed keyset + AAD.
 *   - A vector's decoded sizes drift from the declared `plaintextSize` / `ciphertextSize`.
 *   - The Tink AEAD wire prefix is wrong.
 *   - Tampered ciphertext / wrong AAD / truncated bytes don't reject as expected.
 *
 * Sister tests at sync-core/.../InteropFixtureVerifyTest.kt (producer) and
 * src/crypto/payload.test.ts + src/crypto/streaming-aead.test.ts (octi-web).
 */
class InteropFixtureVerifyTest {

    companion object {
        private lateinit var cacheDir: Path

        @JvmStatic
        @BeforeAll
        fun setUp() {
            cacheDir = InteropFixtureSync.ensureSynced()
        }
    }

    @Test
    fun `manifest sha256 matches every committed fixture file`() {
        // InteropFixtureSync.ensureSynced already validates the manifest's sha against the
        // lockfile and every file's sha against the manifest; re-running the check here would
        // be redundant. Instead pin the visible state: cache directory exists, has both
        // fixture files, and the manifest itself is parseable + at the expected schemaVersion.
        Files.isRegularFile(cacheDir.resolve(InteropFixtures.MANIFEST_FILE)) shouldBe true
        Files.isRegularFile(cacheDir.resolve(InteropFixtures.TINK_FILE)) shouldBe true
        Files.isRegularFile(cacheDir.resolve(InteropFixtures.STREAMING_FILE)) shouldBe true

        val manifest = InteropFixtures.json.decodeFromString(
            FixtureManifest.serializer(),
            Files.readString(cacheDir.resolve(InteropFixtures.MANIFEST_FILE)),
        )
        manifest.schemaVersion shouldBe InteropFixtures.SCHEMA_VERSION
        manifest.files.keys shouldBe setOf(InteropFixtures.TINK_FILE, InteropFixtures.STREAMING_FILE)
    }

    @Test
    fun `tink-vectors schema pins expected shape`() {
        val fixture = loadTinkFixture()
        fixture.gcmsiv.keysetType shouldBe "AES256_GCM_SIV"
        fixture.siv.keysetType shouldBe "AES256_SIV"

        val expectedNames = setOf("empty", "hello-world", "with-special-chars", "approx-10kb")
        fixture.gcmsiv.vectors.assertUniqueNames() shouldBe expectedNames
        fixture.siv.vectors.assertUniqueNames() shouldBe expectedNames
        // SIV is contractually AAD-less; a non-empty aad here would be misleading data.
        for (v in fixture.siv.vectors) v.aad shouldBe ""
    }

    @Test
    fun `tink-vectors gcmsiv decrypts every committed ciphertext`() {
        val fixture = loadTinkFixture()
        val crypti = cryptiFor(fixture.gcmsiv)
        for (v in fixture.gcmsiv.vectors) {
            val plaintext = decodeBase64Required(v.plaintextBase64)
            val aad = v.aad.toByteArray(Charsets.UTF_8)
            val ciphertext = decodeBase64Required(v.ciphertextBase64)
            ciphertext.assertTinkAeadPrefix("gcmsiv:${v.name}")

            val decrypted = crypti.decrypt(ciphertext, aad).gunzip()
            decrypted shouldBe plaintext
        }
    }

    @Test
    fun `tink-vectors siv decrypts every committed ciphertext`() {
        val fixture = loadTinkFixture()
        val crypti = cryptiFor(fixture.siv)
        for (v in fixture.siv.vectors) {
            val plaintext = decodeBase64Required(v.plaintextBase64)
            val ciphertext = decodeBase64Required(v.ciphertextBase64)
            ciphertext.assertTinkAeadPrefix("siv:${v.name}")

            // Legacy SIV ignores AAD by construction.
            val decrypted = crypti.decrypt(ciphertext).gunzip()
            decrypted shouldBe plaintext
        }
    }

    @Test
    fun `gcmsiv decrypt rejects wrong associated data`() {
        val fixture = loadTinkFixture()
        val crypti = cryptiFor(fixture.gcmsiv)
        val v = fixture.gcmsiv.vectors.first { it.plaintextBase64.isNotEmpty() }
        val ct = decodeBase64Required(v.ciphertextBase64)
        val wrongAad = "${v.aad}-tampered".toByteArray(Charsets.UTF_8)
        shouldThrowAny { crypti.decrypt(ct, wrongAad) }
    }

    @Test
    fun `gcmsiv decrypt rejects tampered ciphertext`() {
        val fixture = loadTinkFixture()
        val crypti = cryptiFor(fixture.gcmsiv)
        val v = fixture.gcmsiv.vectors.first { it.plaintextBase64.isNotEmpty() }
        val ct = decodeBase64Required(v.ciphertextBase64).toByteArray()
        // Flip a bit deep inside the body so we exercise the AEAD tag verification path
        // rather than the prefix-mismatch path.
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 0x01).toByte()
        val aad = v.aad.toByteArray(Charsets.UTF_8)
        shouldThrowAny { crypti.decrypt(ct.toByteString(), aad) }
    }

    @Test
    fun `gcmsiv decrypt rejects truncated ciphertext`() {
        val fixture = loadTinkFixture()
        val crypti = cryptiFor(fixture.gcmsiv)
        val v = fixture.gcmsiv.vectors.first { it.plaintextBase64.isNotEmpty() }
        val ct = decodeBase64Required(v.ciphertextBase64).toByteArray()
        val truncated = ct.copyOf(maxOf(6, ct.size - 8))
        val aad = v.aad.toByteArray(Charsets.UTF_8)
        shouldThrowAny { crypti.decrypt(truncated.toByteString(), aad) }
    }

    @Test
    fun `siv decrypt rejects tampered ciphertext`() {
        val fixture = loadTinkFixture()
        val crypti = cryptiFor(fixture.siv)
        val v = fixture.siv.vectors.first { it.plaintextBase64.isNotEmpty() }
        val ct = decodeBase64Required(v.ciphertextBase64).toByteArray()
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 0x01).toByte()
        shouldThrowAny { crypti.decrypt(ct.toByteString()) }
    }

    @Test
    fun `siv decrypt rejects truncated ciphertext`() {
        val fixture = loadTinkFixture()
        val crypti = cryptiFor(fixture.siv)
        val v = fixture.siv.vectors.first { it.plaintextBase64.isNotEmpty() }
        val ct = decodeBase64Required(v.ciphertextBase64).toByteArray()
        val truncated = ct.copyOf(maxOf(6, ct.size - 8))
        shouldThrowAny { crypti.decrypt(truncated.toByteString()) }
    }

    @Test
    fun `streaming-vectors schema pins expected shape`() {
        val fixture = loadStreamingFixture()
        fixture.keysetType shouldBe "AES256_GCM_SIV"
        // Set-based comparison (matches the Tink test) — vector order isn't part of the contract.
        val names = fixture.vectors.map { it.name }
        check(names.size == names.toSet().size) { "duplicate streaming vector names: $names" }
        names.toSet() shouldBe setOf("empty", "short", "two-segments")
        for (v in fixture.vectors) {
            val pattern = v.plaintextPattern
            if (pattern != null) pattern.size shouldBe v.plaintextSize
        }
    }

    @Test
    fun `streaming-vectors decrypts every committed ciphertext`() {
        val fixture = loadStreamingFixture()
        val keyset = PayloadEncryption.KeySet(
            type = fixture.keysetType,
            key = decodeBase64Required(fixture.keysetBase64),
        )
        val cipher = StreamingPayloadCipher(keyset)
        for (v in fixture.vectors) {
            val plaintext = expectedPlaintext(v)
            val ciphertext = decodeBase64Required(v.ciphertextBase64).toByteArray()
            ciphertext.size shouldBe v.ciphertextSize
            // Pin the desktop's expectedCiphertextSize formula against the producer's
            // committed ciphertextSize — catches a Tink-version-shifted header/tag overhead
            // before it shows up as a runtime upload-size mismatch.
            cipher.ciphertextSize(v.plaintextSize.toLong()) shouldBe v.ciphertextSize.toLong()

            val out = Buffer()
            cipher.decrypt(Buffer().write(ciphertext), out, v.aad.toByteArray(Charsets.UTF_8))
            val decrypted = out.readByteArray()
            decrypted.size shouldBe v.plaintextSize
            plaintext.size shouldBe v.plaintextSize
            decrypted.toByteString() shouldBe plaintext.toByteString()
        }
    }

    @Test
    fun `aadFor pins the deviceId moduleId blobKey triple format`() {
        // The producer composes AADs as "deviceId:moduleId:blobKey" — the fixture vectors
        // bake that format in. If desktop's `aadFor` ever drifts, a real upload would silently
        // produce undecodable blobs on the peer. Pin the format against a known fixture AAD.
        val fixture = loadStreamingFixture()
        val cipher = StreamingPayloadCipher(
            PayloadEncryption.KeySet(
                type = fixture.keysetType,
                key = decodeBase64Required(fixture.keysetBase64),
            ),
        )
        val short = fixture.vectors.first { it.name == "short" }
        val computed = cipher.aadFor(
            deviceId = "device-a",
            moduleId = "module-x",
            blobKey = "key-2",
        ).toString(Charsets.UTF_8)
        computed shouldBe short.aad
    }

    @Test
    fun `streaming decrypt rejects wrong aad`() {
        val fixture = loadStreamingFixture()
        val keyset = PayloadEncryption.KeySet(
            type = fixture.keysetType,
            key = decodeBase64Required(fixture.keysetBase64),
        )
        val cipher = StreamingPayloadCipher(keyset)
        val v = fixture.vectors.first { it.plaintextSize > 0 }
        val ct = decodeBase64Required(v.ciphertextBase64).toByteArray()
        val wrongAad = "${v.aad}-tampered".toByteArray(Charsets.UTF_8)
        shouldThrowAny { cipher.decrypt(Buffer().write(ct), Buffer(), wrongAad) }
    }

    @Test
    fun `streaming decrypt rejects truncated ciphertext`() {
        val fixture = loadStreamingFixture()
        val keyset = PayloadEncryption.KeySet(
            type = fixture.keysetType,
            key = decodeBase64Required(fixture.keysetBase64),
        )
        val cipher = StreamingPayloadCipher(keyset)
        val v = fixture.vectors.first { it.plaintextSize > 0 }
        val ct = decodeBase64Required(v.ciphertextBase64).toByteArray()
        val truncated = ct.copyOf(maxOf(1, ct.size - 4))
        shouldThrowAny {
            cipher.decrypt(Buffer().write(truncated), Buffer(), v.aad.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `streaming decrypt rejects corrupt header byte`() {
        val fixture = loadStreamingFixture()
        val keyset = PayloadEncryption.KeySet(
            type = fixture.keysetType,
            key = decodeBase64Required(fixture.keysetBase64),
        )
        val cipher = StreamingPayloadCipher(keyset)
        // Use the multi-segment vector so the corrupt-header path is exercised, not just
        // an "empty input" reject.
        val v = fixture.vectors.first { it.plaintextSize > 1024 }
        val ct = decodeBase64Required(v.ciphertextBase64).toByteArray()
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()
        shouldThrowAny {
            cipher.decrypt(Buffer().write(ct), Buffer(), v.aad.toByteArray(Charsets.UTF_8))
        }
    }

    private fun expectedPlaintext(v: StreamingVector): ByteArray {
        val inline = v.plaintextBase64
        val pattern = v.plaintextPattern
        check((inline != null) xor (pattern != null)) {
            "vector '${v.name}' must declare exactly one of plaintextBase64 / plaintextPattern"
        }
        return when {
            inline != null -> decodeBase64Required(inline).toByteArray()
            pattern != null -> InteropFixtures.materializePattern(pattern)
            else -> error("unreachable")
        }
    }

    private fun loadTinkFixture(): TinkVectorsFixture {
        val raw = Files.readString(cacheDir.resolve(InteropFixtures.TINK_FILE))
        val fixture = InteropFixtures.json.decodeFromString(TinkVectorsFixture.serializer(), raw)
        fixture.schemaVersion shouldBe InteropFixtures.SCHEMA_VERSION
        return fixture
    }

    private fun loadStreamingFixture(): StreamingVectorsFixture {
        val raw = Files.readString(cacheDir.resolve(InteropFixtures.STREAMING_FILE))
        val fixture = InteropFixtures.json.decodeFromString(StreamingVectorsFixture.serializer(), raw)
        fixture.schemaVersion shouldBe InteropFixtures.SCHEMA_VERSION
        return fixture
    }

    private fun cryptiFor(block: KeysetBlock): PayloadEncryption = PayloadEncryption(
        keySet = PayloadEncryption.KeySet(
            type = block.keysetType,
            key = decodeBase64Required(block.keysetBase64),
        ),
    )

    private fun ByteString.assertTinkAeadPrefix(label: String) {
        // 1-byte prefix (0x01) + 4-byte key id + nonce + ciphertext + tag. Empty plaintexts
        // still produce a non-empty ciphertext, so size 5 is the strict minimum.
        check(size >= 5) { "ciphertext for $label too short: $size bytes" }
        this[0] shouldBe InteropFixtures.TINK_PREFIX_BYTE
    }

    private fun List<PayloadVector>.assertUniqueNames(): Set<String> {
        val names = map { it.name }
        check(names.size == names.toSet().size) { "duplicate vector names: $names" }
        return names.toSet()
    }

    private fun List<String>.assertNoDuplicates(label: String): List<String> {
        check(size == toSet().size) { "duplicate $label vector names: $this" }
        return this
    }

    private fun decodeBase64Required(s: String): ByteString =
        s.decodeBase64() ?: error("invalid base64 in committed fixture")

    /**
     * Producer wraps the plaintext in gzip before encrypting (matches OctiServerConnector's
     * wire layering). Verify side mirrors that.
     *
     * No empty-input short-circuit: every committed ciphertext, including the "empty" vector,
     * decrypts to a non-empty gzip frame (header + trailer). A zero-byte decrypt output is
     * itself an interop failure and should propagate as a GZIP parse error.
     */
    private fun ByteString.gunzip(): ByteString {
        val out = java.io.ByteArrayOutputStream()
        GZIPInputStream(java.io.ByteArrayInputStream(toByteArray())).use { it.copyTo(out) }
        return out.toByteArray().toByteString()
    }
}

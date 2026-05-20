package eu.darken.octi.desktop.protocol.encryption.interop

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cross-repo wire-format fixtures owned by d4rken-org/octi (sync-core). This file is the
 * desktop's typed view of the JSON the producer emits — must stay byte-compatible with
 *
 *  - sync-core/src/test/java/.../interop/InteropFixtures.kt (app-main, producer)
 *  - src/__interop__/fixture-loader.ts (octi-web, sibling consumer)
 *
 * If a field rename or a new `plaintextPattern.kind` lands in app-main, mirror it here AND
 * in octi-web in the same coordination window, or the next manifest bump breaks decode.
 */
internal object InteropFixtures {

    const val SCHEMA_VERSION = 1
    const val MANIFEST_FILE = "manifest.json"
    const val TINK_FILE = "tink-vectors.json"
    const val STREAMING_FILE = "streaming-vectors.json"

    /** First byte of every Tink-AEAD-prefixed ciphertext. Pinned so wire drift fails loudly. */
    const val TINK_PREFIX_BYTE: Byte = 0x01

    /** Lenient about unknown keys so a forward-compat field addition upstream doesn't break us. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Reconstruct plaintext bytes for a [StreamingPlaintextPattern]. Mirror of app-main's
     * `InteropFixtures.materializePattern`. New kinds must be added here, on app-main, AND on
     * octi-web at the same time.
     */
    fun materializePattern(pattern: StreamingPlaintextPattern): ByteArray = when (pattern.kind) {
        "sequential" -> ByteArray(pattern.size) { i -> (i and 0xFF).toByte() }
        else -> error("unknown plaintextPattern.kind=${pattern.kind}")
    }
}

@Serializable
internal data class FixtureManifest(
    val schemaVersion: Int,
    val source: String,
    val generator: String,
    val files: Map<String, FileEntry>,
)

@Serializable
internal data class FileEntry(val sha256: String)

@Serializable
internal data class TinkVectorsFixture(
    val schemaVersion: Int,
    val note: String,
    val gcmsiv: KeysetBlock,
    val siv: KeysetBlock,
)

@Serializable
internal data class KeysetBlock(
    val keysetType: String,
    val keysetBase64: String,
    val vectors: List<PayloadVector>,
)

@Serializable
internal data class PayloadVector(
    val name: String,
    val plaintextBase64: String,
    /** UTF-8 string. Empty for legacy SIV by construction. */
    val aad: String,
    val ciphertextBase64: String,
)

@Serializable
internal data class StreamingVectorsFixture(
    val schemaVersion: Int,
    val note: String,
    val keysetType: String,
    val keysetBase64: String,
    val vectors: List<StreamingVector>,
)

@Serializable
internal data class StreamingVector(
    val name: String,
    val aad: String,
    /** Inline plaintext bytes (base64). Mutually exclusive with [plaintextPattern]. */
    val plaintextBase64: String? = null,
    /** Deterministic plaintext reference for large vectors. Mutually exclusive with [plaintextBase64]. */
    val plaintextPattern: StreamingPlaintextPattern? = null,
    val plaintextSize: Int,
    val ciphertextBase64: String,
    val ciphertextSize: Int,
)

@Serializable
internal data class StreamingPlaintextPattern(
    val kind: String,
    val size: Int,
)

/** Internal shape of `fixture-lock.json` at the repo root. */
@Serializable
internal data class FixtureLock(
    val source: String,
    val ref: String,
    @SerialName("manifest_sha256") val manifestSha256: String,
)

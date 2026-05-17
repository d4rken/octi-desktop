package eu.darken.octi.desktop.protocol.encryption

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import eu.darken.octi.desktop.common.log.Logging.Priority.VERBOSE
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import okio.BufferedSink
import okio.Sink
import okio.Source
import okio.buffer
import java.io.OutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AEAD encryption for blob content (OctiServer only).
 *
 * Verbatim port of `sync-core/.../StreamingPayloadCipher` — keep this and the Android source
 * byte-for-byte equivalent. Both sides:
 *  - reject legacy AES256_SIV keysets (streaming AEAD is unsafe with deterministic key material)
 *  - HKDF-SHA256 derive the streaming key from the existing keyset bytes with the same
 *    SALT/INFO constants — that's what guarantees Android and desktop end up with the same
 *    derived key even though they don't exchange any extra material
 *  - Use Tink's [AesGcmHkdfStreaming] with HmacSha256, 32-byte key, 1 MiB segments, prefix=0
 *
 * Diverging here silently breaks blob interop. The C-phase test suite is meant to catch that;
 * before changing any constant, write a cross-platform decrypt test against an Android-produced
 * fixture first.
 */
class StreamingPayloadCipher(keySet: PayloadEncryption.KeySet) {

    private val streamingAead: AesGcmHkdfStreaming

    init {
        val mode = EncryptionMode.fromTypeString(keySet.type)
        require(mode == EncryptionMode.AES256_GCM_SIV) {
            "Only AES256_GCM_SIV keysets are supported for blob streaming encryption (was: ${keySet.type})"
        }

        val derivedKey = hkdfSha256(
            ikm = keySet.key.toByteArray(),
            salt = HKDF_SALT,
            info = HKDF_INFO,
            length = KEY_SIZE_BYTES,
        )

        streamingAead = AesGcmHkdfStreaming(
            derivedKey,
            "HmacSha256",
            KEY_SIZE_BYTES,
            SEGMENT_SIZE,
            0,
        )

        log(TAG, VERBOSE) { "StreamingPayloadCipher initialized (mode=${keySet.type})" }
    }

    /**
     * Number of ciphertext bytes [encrypt] will produce for a plaintext of [plaintextBytes]
     * bytes. Delegates to Tink so we stay in lock-step with whatever header/segment/tag layout
     * the primitive uses. Used by the upload pipeline to declare ciphertext size to the server
     * *before* any bytes have been encrypted, so we don't need to encrypt-to-temp first.
     */
    fun ciphertextSize(plaintextBytes: Long): Long {
        require(plaintextBytes >= 0) { "plaintextBytes must be non-negative (was $plaintextBytes)" }
        return streamingAead.expectedCiphertextSize(plaintextBytes)
    }

    /** Encrypt [source] into [sink] with [associatedData] binding (AAD). */
    fun encrypt(source: Source, sink: Sink, associatedData: ByteArray) {
        log(TAG, VERBOSE) { "encrypt(aad=${associatedData.size}B)" }
        val bufferedSink = sink.buffer()
        source.buffer().inputStream().use { plainIn ->
            streamingAead.newEncryptingStream(NoCloseBufferedSinkOutputStream(bufferedSink), associatedData).use { encOut ->
                plainIn.copyTo(encOut)
            }
        }
        bufferedSink.emit()
    }

    /**
     * Decrypt [source] into [sink] with [associatedData] binding.
     *
     * Streaming AEAD contract: Tink verifies and emits plaintext one segment at a time
     * (SEGMENT_SIZE = 1 MiB). If a later segment's tag fails, earlier segments' plaintext has
     * already been written to [sink]. Callers MUST treat [sink] as valid only when this
     * function returns without throwing — e.g. write to a temp file and re-verify a full-file
     * checksum before exposing the result, don't expose partial output.
     */
    fun decrypt(source: Source, sink: Sink, associatedData: ByteArray) {
        log(TAG, VERBOSE) { "decrypt(aad=${associatedData.size}B)" }
        val bufferedSink = sink.buffer()
        streamingAead.newDecryptingStream(source.buffer().inputStream(), associatedData).use { decIn ->
            decIn.copyTo(NoCloseBufferedSinkOutputStream(bufferedSink))
        }
        bufferedSink.emit()
    }

    /**
     * AAD that binds a blob ciphertext to a specific (deviceId, moduleId, blobKey) triple.
     * Codex review #1: the Android side composes this from those three components — keep the
     * format identical.
     */
    fun aadFor(deviceId: String, moduleId: String, blobKey: String): ByteArray =
        "$deviceId:$moduleId:$blobKey".toByteArray(Charsets.UTF_8)

    companion object {
        private val TAG = logTag("Crypto", "StreamingCipher")

        private const val KEY_SIZE_BYTES = 32
        private const val SEGMENT_SIZE = 1 * 1024 * 1024 // 1 MiB segments
        private val HKDF_SALT = "octi-blob".toByteArray()
        private val HKDF_INFO = "octi-blob-stream-v1".toByteArray()

        /**
         * HKDF-SHA256 extract-and-expand. Returns [length] bytes of derived key material.
         * Uses javax.crypto.Mac directly to avoid pulling in a separate HKDF library.
         */
        internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length <= 32) { "HKDF-SHA256 can produce at most 32 bytes per expand step" }

            // Extract: PRK = HMAC-SHA256(salt, IKM)
            val extractMac = Mac.getInstance("HmacSHA256")
            extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = extractMac.doFinal(ikm)

            // Expand: OKM = HMAC-SHA256(PRK, info || 0x01)
            val expandMac = Mac.getInstance("HmacSHA256")
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(info)
            expandMac.update(0x01.toByte())
            val okm = expandMac.doFinal()

            return okm.copyOf(length)
        }
    }

    /**
     * Tink's streaming AEAD closes its underlying OutputStream when its own try-with-resources
     * ends, which would close the okio BufferedSink and prevent the caller from writing
     * additional bytes after this. We bridge with a wrapper whose close() is a flush — same
     * pattern as the Android source.
     */
    private class NoCloseBufferedSinkOutputStream(
        private val sink: BufferedSink,
    ) : OutputStream() {
        override fun write(oneByte: Int) {
            sink.writeByte(oneByte)
        }

        override fun write(data: ByteArray, offset: Int, byteCount: Int) {
            sink.write(data, offset, byteCount)
        }

        override fun flush() {
            sink.flush()
        }

        override fun close() {
            sink.flush()
        }
    }
}

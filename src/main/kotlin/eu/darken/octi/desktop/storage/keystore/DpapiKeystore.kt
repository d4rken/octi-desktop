package eu.darken.octi.desktop.storage.keystore

import com.sun.jna.platform.win32.Crypt32Util
import eu.darken.octi.desktop.common.files.AtomicWrites
import eu.darken.octi.desktop.platform.PlatformDetector
import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows DPAPI via JNA Crypt32. Ciphertext blobs are stored under
 * `%APPDATA%\<channel-app-name>\secrets\` (stable → `octi`, canary → `octi-canary`) — one file
 * per key. The channel-aware directory comes from [PlatformDetector.configDir], which sources
 * the leaf from [eu.darken.octi.desktop.platform.DesktopIdentity]. DPAPI binds the encryption to
 * the current user account, so the file is unreadable when copied off-machine or accessed by
 * another user.
 *
 * The "stored on disk" aspect is fine here because DPAPI is the on-disk-secrets primitive on
 * Windows — no separate keyring daemon required.
 */
internal class DpapiKeystore(
    private val baseDir: Path = PlatformDetector.configDir().resolve("secrets"),
) : Keystore {

    override val backendDescription: String = "Windows DPAPI (user scope)"

    override fun store(key: String, value: ByteArray) {
        val ciphertext = Crypt32Util.cryptProtectData(value)
        AtomicWrites.writeBytes(filePath(key), ciphertext)
    }

    override fun load(key: String): ByteArray? {
        val path = filePath(key)
        if (!Files.exists(path)) return null
        val ciphertext = Files.readAllBytes(path)
        return try {
            Crypt32Util.cryptUnprotectData(ciphertext)
        } catch (e: Exception) {
            throw KeystoreUnavailableException("DPAPI decrypt failed for $key", e)
        }
    }

    override fun delete(key: String) {
        Files.deleteIfExists(filePath(key))
    }

    private fun filePath(key: String): Path {
        // Sanitize: only [A-Za-z0-9._-] survive; everything else becomes underscore. Prevents
        // path traversal via a malicious key.
        val safe = key.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")
        return baseDir.resolve("$safe.dpapi")
    }
}

package eu.darken.octi.desktop.storage.keystore

import eu.darken.octi.desktop.platform.DesktopIdentity
import java.util.Base64

/**
 * macOS Keychain via the `security` CLI. Cleaner than binding SecKeychain APIs through JNA-Cocoa
 * and Keychain prompts users with a recognizable system dialog when an item is touched.
 *
 * Values are base64-encoded to keep `security`'s -w (password) argument ASCII-safe. The service
 * label is channel-aware via [DesktopIdentity] so canary and stable show up as distinct items
 * in Keychain Access; account becomes the key name so different keys within the same channel
 * get distinct items.
 */
internal class KeychainKeystore(
    private val serviceLabel: String = DesktopIdentity.current.keystoreServiceLabel,
) : Keystore {

    override val backendDescription: String = "macOS Keychain"

    override fun store(key: String, value: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(value)
        // Delete first so add doesn't fail on update.
        runSecurity(listOf("delete-generic-password", "-s", serviceLabel, "-a", key), allowExit = setOf(0, 44))
        val result = runSecurity(listOf("add-generic-password", "-s", serviceLabel, "-a", key, "-w", encoded, "-U"))
        if (result.exitCode != 0) {
            throw KeystoreUnavailableException("Keychain add failed: ${result.stderr}")
        }
    }

    override fun load(key: String): ByteArray? {
        val result = runSecurity(
            listOf("find-generic-password", "-s", serviceLabel, "-a", key, "-w"),
            allowExit = setOf(0, 44),
        )
        if (result.exitCode == 44) return null
        if (result.exitCode != 0) throw KeystoreUnavailableException("Keychain find failed: ${result.stderr}")
        val encoded = result.stdout.trim()
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    override fun delete(key: String) {
        runSecurity(
            listOf("delete-generic-password", "-s", serviceLabel, "-a", key),
            allowExit = setOf(0, 44),
        )
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runSecurity(args: List<String>, allowExit: Set<Int> = setOf(0)): CliResult {
        val process = ProcessBuilder(listOf("/usr/bin/security") + args)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
        val exited = process.waitFor()
        if (exited !in allowExit) {
            throw KeystoreUnavailableException("security $args failed (exit $exited): $stderr")
        }
        return CliResult(exited, stdout, stderr)
    }
}

package eu.darken.octi.desktop.storage.keystore

import java.util.Base64

/**
 * Linux libsecret via the `secret-tool` CLI. We pipe the value to stdin so it doesn't appear in
 * the process argument list.
 *
 * Requires the `secret-tool` binary on PATH (Debian/Ubuntu package: `libsecret-tools`,
 * Fedora/RHEL: `libsecret`). If the binary is missing or the Secret Service D-Bus daemon is
 * unavailable (headless server, no keyring), construction throws so the caller can fall back to
 * [PassphraseFallbackKeystore].
 */
internal class LibsecretKeystore(
    private val serviceLabel: String = "eu.darken.octi.desktop",
) : Keystore {

    override val backendDescription: String = "Linux libsecret (Secret Service)"

    init {
        // `secret-tool` has no --version / --help that exits cleanly (both exit 2 because the
        // parser only knows the subcommand verbs). Probe with a benign `lookup` against an
        // attribute that almost certainly isn't set: exit 0 = unexpected hit (still proves
        // the daemon is up), exit 1 = miss (the happy probe path), anything else = no daemon
        // or no binary, fall back.
        val probe = runSecretTool(
            args = listOf("lookup", "_octi_probe", "_octi_probe_value"),
            input = null,
            allowExit = setOf(0, 1),
        )
        if (probe.exitCode != 0 && probe.exitCode != 1) {
            throw KeystoreUnavailableException("secret-tool unavailable: ${probe.stderr}")
        }
    }

    override fun store(key: String, value: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(value)
        val result = runSecretTool(
            listOf("store", "--label", "$serviceLabel:$key", "service", serviceLabel, "account", key),
            input = encoded.toByteArray(Charsets.UTF_8),
        )
        if (result.exitCode != 0) throw KeystoreUnavailableException("secret-tool store failed: ${result.stderr}")
    }

    override fun load(key: String): ByteArray? {
        val result = runSecretTool(
            listOf("lookup", "service", serviceLabel, "account", key),
            input = null,
            allowExit = setOf(0, 1),
        )
        if (result.exitCode == 1) return null
        if (result.exitCode != 0) throw KeystoreUnavailableException("secret-tool lookup failed: ${result.stderr}")
        val encoded = result.stdout.trim()
        if (encoded.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    override fun delete(key: String) {
        runSecretTool(
            listOf("clear", "service", serviceLabel, "account", key),
            input = null,
            allowExit = setOf(0, 1),
        )
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runSecretTool(args: List<String>, input: ByteArray?, allowExit: Set<Int> = setOf(0)): CliResult {
        val process = ProcessBuilder(listOf("secret-tool") + args)
            .redirectErrorStream(false)
            .start()
        if (input != null) {
            process.outputStream.use { it.write(input) }
        } else {
            process.outputStream.close()
        }
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
        val exited = process.waitFor()
        if (exited !in allowExit) {
            throw KeystoreUnavailableException("secret-tool $args failed (exit $exited): $stderr")
        }
        return CliResult(exited, stdout, stderr)
    }
}

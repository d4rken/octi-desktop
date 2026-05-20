package eu.darken.octi.desktop.screenshots

import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.LinkingData
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * One-shot utility that boots a "phantom phone" peer against a sync-server instance, so the
 * screenshot CI workflow can drive the desktop through a populated dashboard.
 *
 * Concretely, this:
 *   1. Generates a fresh AES-256-GCM-SIV Tink keyset.
 *   2. Registers a brand-new device on the target sync-server (= a brand-new account).
 *   3. Uploads a [MetaInfo] payload for that peer with `deviceType = PHONE`, so it shows up in
 *      the device list with a label/platform instead of as an anonymous id.
 *   4. Creates a share code on the same account.
 *   5. Wraps `(serverAddress, shareCode, encryptionKeyset)` into a [LinkingData] string and
 *      writes it to the requested output file.
 *
 * The desktop then pastes that string into its Linking screen (via the `linking.submit` debug
 * RPC action). The result: a 2-device account (phantom phone + desktop) on a local sync-server,
 * with the phone's meta tile already populated.
 *
 * Not part of the shipped product — invoked only by the `bootstrapScreenshotPeer` Gradle task.
 */
object PeerSeeder {

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = try {
            parseArgs(args)
        } catch (e: IllegalArgumentException) {
            System.err.println("usage: PeerSeeder --server <url> --label <name> --output <path>")
            System.err.println(e.message)
            exitProcess(2)
        }

        runBlocking {
            val linking = seed(
                serverAddress = parsed.serverAddress,
                peerLabel = parsed.label,
            )
            val encoded = linking.toEncodedString(Serialization.json)
            File(parsed.outputPath).writeText(encoded)
            println("PeerSeeder: wrote linking blob (${encoded.length} chars) to ${parsed.outputPath}")
        }
    }

    private suspend fun seed(serverAddress: OctiServer.Address, peerLabel: String): LinkingData {
        val crypto = PayloadEncryption()
        val keyset = crypto.exportKeyset()

        val peerDeviceId = DeviceId(UUID.randomUUID().toString())
        val anonClient = OctiServerHttpClient(
            address = serverAddress,
            deviceId = peerDeviceId,
            deviceMetadata = DeviceMetadata(
                version = "1.0.0-screenshot",
                platform = "android",
                label = peerLabel,
            ),
            credentials = null,
        )
        val registration = anonClient.use { it.register(shareCode = null) }

        val authClient = OctiServerHttpClient(
            address = serverAddress,
            deviceId = peerDeviceId,
            deviceMetadata = DeviceMetadata(
                version = "1.0.0-screenshot",
                platform = "android",
                label = peerLabel,
            ),
            credentials = OctiServer.Credentials(
                serverAdress = serverAddress,
                accountId = OctiServer.Credentials.AccountId(registration.accountID),
                devicePassword = OctiServer.Credentials.DevicePassword(registration.password),
                encryptionKeyset = keyset,
            ),
        )

        return authClient.use { client ->
            val meta = MetaInfo(
                deviceLabel = peerLabel,
                deviceId = peerDeviceId,
                octiVersionName = "1.0.0",
                octiGitSha = "screenshot-fixture",
                deviceManufacturer = "Acme",
                deviceName = "Phantom Phone",
                deviceType = MetaInfo.DeviceType.PHONE,
                deviceBootedAt = Clock.System.now(),
                androidVersionName = "15",
                androidApiLevel = 35,
                androidSecurityPatch = "2026-04-05",
                osType = "android",
                osVersionName = "15",
            )
            val plaintext = Serialization.json.encodeToString(MetaInfo.serializer(), meta)
                .toByteArray(Charsets.UTF_8)
            val aad = "${peerDeviceId.id}:${ModuleIds.META.id}".toByteArray(Charsets.UTF_8)
            val gzipped = plaintext.toByteString().toGzip()
            val ciphertext = crypto.encrypt(gzipped, aad).toByteArray()
            client.writeModule(ModuleIds.META, ciphertext)

            val shareResponse = client.createShareCode()
            LinkingData(
                serverAdress = serverAddress,
                linkCode = OctiServer.Credentials.LinkCode(shareResponse.shareCode),
                encryptionKeyset = keyset,
            )
        }
    }

    private data class Args(
        val serverAddress: OctiServer.Address,
        val label: String,
        val outputPath: String,
    )

    private fun parseArgs(args: Array<String>): Args {
        var server: String? = null
        var label: String? = null
        var output: String? = null
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--server" -> { server = args.getOrNull(i + 1); i += 2 }
                "--label" -> { label = args.getOrNull(i + 1); i += 2 }
                "--output" -> { output = args.getOrNull(i + 1); i += 2 }
                else -> throw IllegalArgumentException("unknown argument: $arg")
            }
        }
        val serverStr = requireNotNull(server) { "--server is required" }
        val resolvedLabel = requireNotNull(label) { "--label is required" }
        val resolvedOutput = requireNotNull(output) { "--output is required" }
        val uri = URI(serverStr)
        require(uri.host != null && uri.scheme != null) { "--server must be a full URL (e.g. http://localhost:18080)" }
        val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return Args(
            serverAddress = OctiServer.Address(
                domain = uri.host,
                protocol = uri.scheme,
                port = port,
            ),
            label = resolvedLabel,
            outputPath = resolvedOutput,
        )
    }
}

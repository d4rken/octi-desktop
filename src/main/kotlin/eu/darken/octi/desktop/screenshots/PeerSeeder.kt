package eu.darken.octi.desktop.screenshots

import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.apps.AppsInfo
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.modules.connectivity.ConnectivityInfo
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.modules.power.PowerInfo
import eu.darken.octi.desktop.protocol.modules.wifi.WifiInfo
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.LinkingData
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
            val now = Clock.System.now()
            // Boot the phone 3 hours ago so the "Up" line on the device tile shows a
            // believable runtime rather than "<1m".
            val bootedAt = now - 3.hours

            writeEncrypted(client, crypto, peerDeviceId, ModuleIds.META, MetaInfo.serializer(), MetaInfo(
                deviceLabel = peerLabel,
                deviceId = peerDeviceId,
                octiVersionName = "1.0.0",
                octiGitSha = "screenshot-fixture",
                deviceManufacturer = "Google",
                deviceName = "Phantom Phone",
                deviceType = MetaInfo.DeviceType.PHONE,
                deviceBootedAt = bootedAt,
                androidVersionName = "15",
                androidApiLevel = 35,
                androidSecurityPatch = "2026-04-05",
                osType = "android",
                osVersionName = "15",
            ))

            writeEncrypted(client, crypto, peerDeviceId, ModuleIds.POWER, PowerInfo.serializer(), PowerInfo(
                status = PowerInfo.Status.DISCHARGING,
                battery = PowerInfo.Battery(level = 78, scale = 100, health = 2, temp = 28.5f),
                // currentNow is reported in microamps on Android; negative = discharging.
                chargeIO = PowerInfo.ChargeIO(
                    currentNow = -1_350_000,
                    currenAvg = -1_400_000,
                    fullSince = null,
                    fullAt = null,
                    emptyAt = now + 4.hours,
                ),
            ))

            writeEncrypted(client, crypto, peerDeviceId, ModuleIds.WIFI, WifiInfo.serializer(), WifiInfo(
                currentWifi = WifiInfo.Wifi(
                    ssid = "HomeNet-5G",
                    reception = 0.85f,
                    freqType = WifiInfo.Wifi.Type.FIVE_GHZ,
                ),
            ))

            writeEncrypted(client, crypto, peerDeviceId, ModuleIds.CONNECTIVITY, ConnectivityInfo.serializer(), ConnectivityInfo(
                connectionType = ConnectivityInfo.ConnectionType.WIFI,
                // 203.0.113.x is TEST-NET-3 reserved for documentation, ideal for a fake
                // public IP that won't ever resolve.
                publicIp = "203.0.113.42",
                localAddressIpv4 = "192.168.1.42",
                localAddressIpv6 = "fe80::1234:5678:9abc:def0",
                gatewayIp = "192.168.1.1",
                dnsServers = listOf("1.1.1.1", "9.9.9.9"),
            ))

            writeEncrypted(client, crypto, peerDeviceId, ModuleIds.CLIPBOARD, ClipboardInfo.serializer(), ClipboardInfo(
                type = ClipboardInfo.Type.SIMPLE_TEXT,
                data = "Hello from Octi!".encodeUtf8(),
            ))

            writeEncrypted(client, crypto, peerDeviceId, ModuleIds.APPS, AppsInfo.serializer(), AppsInfo(
                installedPackages = fakeApps(installedSince = now - 30.days),
            ))

            val shareResponse = client.createShareCode()
            LinkingData(
                serverAdress = serverAddress,
                linkCode = OctiServer.Credentials.LinkCode(shareResponse.shareCode),
                encryptionKeyset = keyset,
            )
        }
    }

    /**
     * gzip → encrypt with AAD `"${deviceId}:${moduleId}"` → POST. Matches the wire shape Android
     * peers and the desktop's [eu.darken.octi.desktop.modules.meta.MetaWriter] use, so consumers
     * decrypt these payloads transparently.
     */
    private suspend fun <T> writeEncrypted(
        client: OctiServerHttpClient,
        crypto: PayloadEncryption,
        deviceId: DeviceId,
        moduleId: ModuleId,
        serializer: KSerializer<T>,
        payload: T,
    ) {
        val plaintext = Serialization.json.encodeToString(serializer, payload).toByteArray(Charsets.UTF_8)
        val aad = "${deviceId.id}:${moduleId.id}".toByteArray(Charsets.UTF_8)
        val gzipped = plaintext.toByteString().toGzip()
        val ciphertext = crypto.encrypt(gzipped, aad).toByteArray()
        client.writeModule(moduleId, ciphertext)
    }

    /**
     * A small but plausible installed-app list. Count matters more than per-package detail —
     * the Apps tile shows `N apps` and an icon, nothing else, so the goal is "looks like a real
     * phone" rather than "shows specific app names".
     */
    private fun fakeApps(installedSince: kotlin.time.Instant): List<AppsInfo.Pkg> = listOf(
        "com.android.chrome" to "Chrome",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.maps" to "Maps",
        "com.google.android.youtube" to "YouTube",
        "com.spotify.music" to "Spotify",
        "com.whatsapp" to "WhatsApp",
        "org.telegram.messenger" to "Telegram",
        "com.discord" to "Discord",
        "com.instagram.android" to "Instagram",
        "com.reddit.frontpage" to "Reddit",
        "com.github.android" to "GitHub",
        "com.duckduckgo.mobile.android" to "DuckDuckGo",
        "org.mozilla.firefox" to "Firefox",
        "org.signal" to "Signal",
        "com.microsoft.office.outlook" to "Outlook",
        "com.slack" to "Slack",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.netflix.mediaclient" to "Netflix",
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.uber.app" to "Uber",
        "eu.darken.octi" to "Octi",
    ).mapIndexed { idx, (pkg, label) ->
        AppsInfo.Pkg(
            packageName = pkg,
            label = label,
            versionCode = 100L + idx,
            versionName = "1.${idx}.0",
            installedAt = installedSince,
            installerPkg = "com.android.vending",
            updatedAt = installedSince,
        )
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

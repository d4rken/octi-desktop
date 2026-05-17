package eu.darken.octi.desktop.modules.meta

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import okio.ByteString.Companion.toByteString
import java.lang.ProcessHandle
import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

private val TAG = logTag("Module", "Meta", "Writer")

/**
 * Periodically writes this desktop's [MetaInfo] to `/v1/module/eu.darken.octi.module.core.meta`
 * so peers can see us in the device list with a label, OS, uptime, and version.
 *
 * Fields the Android schema requires that aren't meaningful on desktop (Android version, API
 * level, security patch, deviceType PHONE/TABLET) are emitted as placeholders per the scope
 * decision: don't fork the schema for MVP. Android UI handles UNKNOWN/empty gracefully.
 *
 * Cadence: write on transition to a fresh active client (cold-start a peer can see us
 * immediately on link), then every [WRITE_INTERVAL]. We skip writes if the data hasn't changed
 * — saves rate-limit budget and avoids triggering a no-op WS broadcast across peers.
 */
class MetaWriter(private val graph: AppGraph) {

    private var lastWrittenPayload: ByteArray? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        graph.activeClient
            .flatMapLatest<OctiServerHttpClient?, Unit> { client ->
                if (client == null) {
                    lastWrittenPayload = null
                    flowOf(Unit)
                } else {
                    writeLoop(client)
                }
            }
            .launchIn(graph.appScope)
    }

    private fun writeLoop(client: OctiServerHttpClient): Flow<Unit> = flow {
        while (true) {
            try {
                val info = buildMetaInfo()
                val credentials = graph.credentialsStore.load()
                if (credentials == null) {
                    log(TAG, WARN) { "No credentials loaded during meta write; skipping" }
                } else {
                    val crypto = PayloadEncryption(keySet = credentials.encryptionKeyset)
                    val plaintext = Serialization.json.encodeToString(MetaInfo.serializer(), info)
                        .toByteArray(Charsets.UTF_8)
                    if (plaintext.contentEquals(lastWrittenPayload)) {
                        log(TAG, DEBUG) { "Meta payload unchanged since last write; skipping" }
                    } else {
                        val ciphertext = crypto.encrypt(plaintext.toByteString()).toByteArray()
                        client.writeModule(ModuleIds.META, ciphertext)
                        lastWrittenPayload = plaintext
                        log(TAG, DEBUG) { "Meta payload written (${plaintext.size}B plaintext, ${ciphertext.size}B ciphertext)" }
                    }
                }
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "Meta write failed; will retry on next tick" }
            }
            emit(Unit)
            delay(WRITE_INTERVAL.inWholeMilliseconds)
        }
    }

    private fun buildMetaInfo(): MetaInfo = MetaInfo(
        deviceLabel = graph.settings.data.deviceLabel,
        deviceId = DeviceId(graph.deviceId.id),
        octiVersionName = DeviceMetadataProvider.APP_VERSION,
        octiGitSha = OCTI_GIT_SHA_PLACEHOLDER,
        deviceManufacturer = (System.getProperty("java.vendor")?.takeIf { it.isNotBlank() }
            ?: "JVM Desktop"),
        deviceName = hostnameOrUnknown(),
        // Per scope decision: desktops report UNKNOWN since neither PHONE nor TABLET fits.
        deviceType = MetaInfo.DeviceType.UNKNOWN,
        deviceBootedAt = processStartInstant(),
        // Android-only schema fields. Send empty/placeholder so the wire payload is valid;
        // Android dashboards render these as "—" or hide them. If MetaInfo v2 with a desktop
        // discriminator lands later, swap these for proper desktop fields then.
        androidVersionName = "",
        androidApiLevel = 0,
        androidSecurityPatch = null,
    )

    private fun processStartInstant(): Instant {
        return try {
            val started = ProcessHandle.current().info().startInstant().orElse(null)
            started?.toKotlinInstant() ?: Clock.System.now()
        } catch (_: Exception) {
            Clock.System.now()
        }
    }

    private fun hostnameOrUnknown(): String = try {
        InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() } ?: "octi-desktop"
    } catch (_: Exception) {
        "octi-desktop"
    }

    companion object {
        private val WRITE_INTERVAL = 5.minutes

        // The Android source generates this from git via CommitHashValueSource at build time.
        // Desktop doesn't have that wiring yet — slot in a placeholder; replace when the
        // packaging phase (H) adds a similar BuildInfo generator.
        private const val OCTI_GIT_SHA_PLACEHOLDER = "desktop-dev"
    }
}

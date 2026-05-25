package eu.darken.octi.desktop.di

import eu.darken.octi.desktop.common.coroutine.AppScope
import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.blob.BlobDownloader
import eu.darken.octi.desktop.debug.rpc.DebugActionRegistry
import eu.darken.octi.desktop.linking.CredentialsStore
import eu.darken.octi.desktop.linking.LinkController
import eu.darken.octi.desktop.linking.LinkResult
import eu.darken.octi.desktop.linking.UnlinkResult
import eu.darken.octi.desktop.modules.clipboard.ClipboardSync
import eu.darken.octi.desktop.modules.files.FileShareRepo
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import eu.darken.octi.desktop.modules.meta.MetaWriter
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.modules.power.PowerInfo
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.storage.Settings
import eu.darken.octi.desktop.storage.keystore.Keystore
import eu.darken.octi.desktop.storage.keystore.KeystoreFactory
import eu.darken.octi.desktop.sync.DeviceListRepo
import eu.darken.octi.desktop.sync.ModuleReader
import eu.darken.octi.desktop.sync.ModuleResolver
import eu.darken.octi.desktop.sync.OctiServerWebSocketClient
import eu.darken.octi.desktop.sync.SyncEventBus
import eu.darken.octi.desktop.ui.dashboard.DashboardModuleRepo
import eu.darken.octi.desktop.ui.files.orderBlobDownloadSources
import eu.darken.octi.desktop.ui.nav.Navigator
import eu.darken.octi.desktop.ui.nav.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

private val TAG = logTag("AppGraph")

/**
 * Manual DI factory. Constructed once in [eu.darken.octi.desktop.Main.main] and threaded down
 * into Compose via a CompositionLocal in the UI tree.
 *
 * The graph models the two top-level app states explicitly:
 * - Pre-link: [activeConnectors] is empty, navigator starts at [Screen.Linking].
 * - Post-link: [activeConnectors] is non-empty; navigator starts at [Screen.Dashboard].
 *
 * Linking and unlinking mutate [activeConnectors] via [onLinked] / [unlink]. Each connector
 * owns its own Ktor HttpClient via the wrapping [OctiServerConnector]; on unlink the connector
 * is removed and its `close()` tears the client down.
 *
 * Multi-connector aware: [activeConnectors] is the canonical list of linked connectors;
 * [runningConnectors] filters out the paused ones for poll/write loops. Consumers that need
 * "any connector at all?" check `activeConnectors.value.isEmpty()` directly. The earlier
 * `primaryConnector` helper was removed once every consumer migrated.
 *
 * The passphrase prompt for the keystore fallback is wired here too — it's invoked lazily by
 * [KeystoreFactory] only when no OS keystore is available.
 */
class AppGraph private constructor(
    val appScope: AppScope,
    val settings: Settings,
    val keystore: Keystore,
    val credentialsStore: CredentialsStore,
    val deviceId: DeviceId,
    val linkController: LinkController,
    val navigator: Navigator,
    initialConnectors: List<OctiServerConnector>,
) {

    private val _activeConnectors = MutableStateFlow(initialConnectors)
    val activeConnectors: StateFlow<List<OctiServerConnector>> = _activeConnectors.asStateFlow()

    /**
     * Linked connectors that are NOT paused. Polling loops (DeviceListRepo), websocket sessions
     * (OctiServerWebSocketClient), and (in PR-4) writers consume this so a paused connector
     * stops contributing traffic without being unlinked. Settings UI keeps reading the full
     * [activeConnectors] list so paused entries still render with their "Resume" toggle.
     */
    val runningConnectors: StateFlow<List<OctiServerConnector>> =
        combine(activeConnectors, settings.flow) { connectors, snapshot ->
            connectors.filterNot { snapshot.connectors[it.identifier.idString]?.paused == true }
        }.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = initialConnectors.filterNot {
                settings.data.connectors[it.identifier.idString]?.paused == true
            },
        )

    /**
     * Created eagerly so its [activeConnectors] collector is wired before any UI subscribes.
     * Constructed lazily — the constructor reads [activeConnectors], which must be initialized
     * first. Property-order matters: keep these below the [_activeConnectors] field.
     */
    val syncEventBus: SyncEventBus = SyncEventBus()
    val deviceListRepo: DeviceListRepo by lazy { DeviceListRepo(this) }
    val moduleResolver: ModuleResolver by lazy { ModuleResolver(this) }
    val moduleReader: ModuleReader by lazy { ModuleReader(this) }
    val webSocketClient: OctiServerWebSocketClient by lazy {
        OctiServerWebSocketClient(this, syncEventBus)
    }
    val metaWriter: MetaWriter by lazy { MetaWriter(this) }
    val clipboardSync: ClipboardSync by lazy { ClipboardSync(this) }
    val fileShareRepo: FileShareRepo by lazy { FileShareRepo(this) }
    val dashboardModuleRepo: DashboardModuleRepo by lazy { DashboardModuleRepo(this) }

    /**
     * Debug RPC action registry. Always present (no overhead when the server is off — it's just
     * a map). Populated with graph-level actions in [registerDebugActions], invoked from the
     * factory after construction so `this` is fully wired.
     */
    val debugActions: DebugActionRegistry = DebugActionRegistry()

    init {
        if (initialConnectors.isNotEmpty()) {
            log(TAG, Logging.Priority.INFO) {
                "Booted with ${initialConnectors.size} connector(s): " +
                    initialConnectors.joinToString { it.identifier.idString }
            }
        } else {
            log(TAG, Logging.Priority.INFO) { "No stored connectors; starting in Linking flow" }
        }
    }

    /**
     * Called by the Link flow on [LinkResult.Success]. [LinkController] has already committed
     * the keystore entry AND the [Settings] discovery entry; this is purely in-memory state
     * mutation: build the connector and add it to [activeConnectors].
     *
     * Navigation is the caller's responsibility — a first-time link wants to clear-stack to
     * Dashboard while "add another account" wants to return to wherever the user came from.
     * The view model passes the right [Screen] based on its [eu.darken.octi.desktop.ui.linking.LinkingPurpose].
     */
    fun onLinked(connectorId: ConnectorId, credentials: OctiServer.Credentials) {
        val metadata = DeviceMetadataProvider.current(userLabel = settings.data.deviceLabel)
        val connector = OctiServerConnector.fromCredentials(
            deviceId = deviceId,
            deviceMetadata = metadata,
            credentials = credentials,
        )
        // Replace any pre-existing connector with the same identifier (e.g. relink after a
        // crashed previous unlink) — close the old client to free the connection pool.
        _activeConnectors.update { existing ->
            existing.firstOrNull { it.identifier == connectorId }?.let {
                runCatching { it.close() }
                    .onFailure { e -> log(TAG, Logging.Priority.WARN, e) { "Old connector close() failed" } }
            }
            existing.filterNot { it.identifier == connectorId } + connector
        }
    }

    /**
     * Flip a connector's paused flag in `Settings.connectors`. The reactive [runningConnectors]
     * derives from it, so the per-connector WS loop in [OctiServerWebSocketClient] and the poll
     * loop in [DeviceListRepo] cancel/restart on their next emission without any extra wiring
     * here. UI calls this from the Settings card's Pause toggle.
     */
    fun setPaused(connectorId: ConnectorId, paused: Boolean) {
        val key = connectorId.idString
        settings.update { current ->
            val entry = current.connectors[key] ?: return@update current
            if (entry.paused == paused) current
            else current.copy(connectors = current.connectors + (key to entry.copy(paused = paused)))
        }
        log(TAG, Logging.Priority.INFO) {
            "setPaused(${connectorId.logLabel}, paused=$paused)"
        }
    }

    /**
     * Registers the always-available graph-level debug actions. Called once by the factory.
     *
     * All UI-mutating actions go through [DebugActionRegistry.registerUiAction] so they hop to
     * the AWT EDT before touching Compose state or [Navigator]'s [MutableStateFlow].
     */
    private fun registerDebugActions() {
        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "dashboard.refresh",
                description = "Force-refresh the device list now (skips the poll interval).",
            ),
        ) {
            deviceListRepo.kick()
            buildJsonObject { put("kicked", JsonPrimitive(true)) }
        }

        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "navigation.go",
                description = "Jump directly to a screen. Top-level routes: linking, dashboard, clipboard, settings. To open a device's Files screen, pass route=files together with a deviceId.",
                params = mapOf(
                    "route" to "one of: linking|dashboard|clipboard|settings|files",
                    "deviceId" to "string — required when route=files",
                ),
                example = """{"route":"settings"}""",
            ),
        ) { params ->
            val route = params["route"]?.jsonPrimitive?.content ?: error("missing route")
            val screen: Screen = when (route) {
                "linking" -> Screen.Linking
                "dashboard" -> Screen.Dashboard
                "clipboard" -> Screen.Clipboard
                "settings" -> Screen.Settings
                "files" -> {
                    val target = params["deviceId"]?.jsonPrimitive?.content
                        ?: error("route=files requires deviceId")
                    Screen.Files(target)
                }
                else -> error("unknown route: $route")
            }
            navigator.navigateTo(screen)
            buildJsonObject { put("screen", JsonPrimitive(route)) }
        }

        debugActions.register(
            DebugActionRegistry.Metadata(
                name = "linking.submit",
                description = "Submit a link code as if pasted into the Linking screen.",
                params = mapOf("code" to "raw share-code string from the Android app"),
                example = """{"code":"<base64-share-code>"}""",
            ),
        ) { params ->
            val code = params["code"]?.jsonPrimitive?.content ?: error("missing code")
            val result = linkController.link(code, deviceId)
            if (result is LinkResult.Success) {
                onLinked(result.connectorId, result.credentials)
                // Debug-RPC drives the first-link path (used by screenshot CI + manual testing);
                // matches the firstLink behavior of the LinkingViewModel.
                navigator.navigateTo(Screen.Dashboard, clearStack = true)
            }
            buildJsonObject {
                put("result", JsonPrimitive(result::class.simpleName ?: "Unknown"))
            }
        }

        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "account.unlink",
                description = "Unlink the first linked connector: calls DELETE /v1/devices/{self} " +
                    "then clears local credentials + settings entry. Returns the UnlinkResult " +
                    "variant name so callers can branch on Success / NetworkError. With multiple " +
                    "connectors, this targets the first in activeConnectors — the screenshot CI " +
                    "flow only ever sets up one, so 'first' is unambiguous there.",
            ),
        ) {
            val target = activeConnectors.value.firstOrNull()?.identifier
            val result = if (target == null) UnlinkResult.NotLinked else unlink(target)
            buildJsonObject {
                put("result", JsonPrimitive(result::class.simpleName ?: "Unknown"))
            }
        }

        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "settings.themeMode",
                description = "Override the UI theme. Used by the screenshot workflow to capture light + dark variants of the same screen.",
                params = mapOf("mode" to "one of: SYSTEM|LIGHT|DARK"),
                example = """{"mode":"DARK"}""",
            ),
        ) { params ->
            val raw = params["mode"]?.jsonPrimitive?.content ?: error("missing mode")
            val mode = eu.darken.octi.desktop.storage.ThemeMode.entries
                .firstOrNull { it.name == raw }
                ?: error("unknown mode: $raw")
            settings.update { it.copy(themeMode = mode) }
            buildJsonObject { put("mode", JsonPrimitive(mode.name)) }
        }

        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "settings.deviceLabel",
                description = "Override the desktop's device label. Used by the screenshot workflow so the dashboard tile reads a polished name (e.g. \"MacBook Pro\") instead of the runner's hostname. Pass an empty string to clear the override and fall back to the hostname.",
                params = mapOf("label" to "string — new label, or empty to clear"),
                example = """{"label":"MacBook Pro"}""",
            ),
        ) { params ->
            val label = params["label"]?.jsonPrimitive?.content ?: error("missing label")
            settings.update { it.copy(deviceLabel = label.ifBlank { null }) }
            buildJsonObject { put("label", JsonPrimitive(label)) }
        }

        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "settings.pause",
                description = "Pause or resume a single connector by its idString. Pausing cancels " +
                    "that connector's WebSocket + poll loops (its webSocketState in /dev/state goes " +
                    "to Idle) while leaving the others running. Throws connector_not_found if the id " +
                    "isn't in activeConnectors — setPaused alone silently no-ops on an unknown key.",
                params = mapOf(
                    "connectorId" to "string — a connectors[].id from /dev/state",
                    "paused" to "boolean — true to pause, false to resume",
                ),
                example = """{"connectorId":"octiserver-host-acct","paused":true}""",
            ),
        ) { params ->
            val connectorId = params["connectorId"]?.jsonPrimitive?.content ?: error("missing connectorId")
            val paused = params["paused"]?.jsonPrimitive?.boolean ?: error("missing paused")
            val target = activeConnectors.value.firstOrNull { it.identifier.idString == connectorId }
                ?: error("connector_not_found: $connectorId")
            setPaused(target.identifier, paused)
            buildJsonObject {
                put("connectorId", JsonPrimitive(connectorId))
                put("paused", JsonPrimitive(paused))
            }
        }

        debugActions.register(
            DebugActionRegistry.Metadata(
                name = "module.read",
                description = "Read + decrypt a single module for a device through the real " +
                    "ModuleReader/ModuleResolver path (resolves the freshest connector source and " +
                    "decrypts with that connector's keyset). Proves a module document actually " +
                    "decoded — unlike /dev/state knownDevices, which only echoes /v1/devices " +
                    "registration headers. Returns the decrypted module as JSON.",
                params = mapOf(
                    "deviceId" to "string — target device id (own or a peer)",
                    "moduleId" to "one of: meta|clipboard|power",
                ),
                example = """{"deviceId":"<uuid>","moduleId":"meta"}""",
            ),
        ) { params ->
            val deviceId = DeviceId(params["deviceId"]?.jsonPrimitive?.content ?: error("missing deviceId"))
            when (val moduleId = params["moduleId"]?.jsonPrimitive?.content ?: error("missing moduleId")) {
                "meta" -> readModuleAsJson(ModuleIds.META, deviceId, MetaInfo.serializer())
                "clipboard" -> readModuleAsJson(ModuleIds.CLIPBOARD, deviceId, ClipboardInfo.serializer())
                "power" -> readModuleAsJson(ModuleIds.POWER, deviceId, PowerInfo.serializer())
                else -> error("unknown moduleId: $moduleId (expected meta|clipboard|power)")
            }
        }

        debugActions.register(
            DebugActionRegistry.Metadata(
                name = "files.share",
                description = "Share a local file from this desktop, fanning the blob out to every " +
                    "running GCM-SIV connector. Returns the minted blobKey, the connectors the blob " +
                    "landed on (connectorRefs), and committedConnectorCount (how many servers " +
                    "accepted the FileShareInfo document — distinct from the blob fan-out).",
                params = mapOf("path" to "string — absolute path to a regular file on this host"),
                example = """{"path":"/tmp/share.bin"}""",
            ),
        ) { params ->
            val pathStr = params["path"]?.jsonPrimitive?.content ?: error("missing path")
            val path = Path.of(pathStr)
            // Minimal guardrail: a clean error instead of an opaque uploader failure on a
            // directory / missing file. Symlinks are followed deliberately — this is an opt-in
            // dev tool, not a hardening boundary (see rules/debug-rpc.md threat model).
            if (!Files.isRegularFile(path)) error("not_a_regular_file: $pathStr")
            when (val result = fileShareRepo.shareFile(path)) {
                is FileShareRepo.ShareResult.Ok -> {
                    val sharedFile = result.sharedFile ?: error("share returned Ok without a SharedFile")
                    buildJsonObject {
                        put("result", JsonPrimitive("Ok"))
                        put("blobKey", JsonPrimitive(sharedFile.blobKey))
                        put("connectorRefs", buildJsonArray {
                            sharedFile.connectorRefs.keys.sorted().forEach { add(JsonPrimitive(it)) }
                        })
                        put("connectorRefCount", JsonPrimitive(sharedFile.connectorRefs.size))
                        put("committedConnectorCount", JsonPrimitive(result.committedConnectorCount))
                    }
                }
                else -> error("share_failed: ${result::class.simpleName}")
            }
        }

        debugActions.register(
            DebugActionRegistry.Metadata(
                name = "files.download",
                description = "Download a shared file (by blobKey) owned by deviceId to destination, " +
                    "routing through orderBlobDownloadSources + BlobDownloader exactly like the Files " +
                    "screen. Returns servedBy — the connector that actually served the blob — so " +
                    "callers can assert source preference (running connectors win over paused).",
                params = mapOf(
                    "deviceId" to "string — owner device id (use the desktop's own id for own shares)",
                    "blobKey" to "string — blobKey from files.share",
                    "destination" to "string — absolute path to write the decrypted file to",
                ),
                example = """{"deviceId":"<uuid>","blobKey":"<uuid>","destination":"/tmp/dl.bin"}""",
            ),
        ) { params ->
            val ownerDeviceId = DeviceId(params["deviceId"]?.jsonPrimitive?.content ?: error("missing deviceId"))
            val blobKey = params["blobKey"]?.jsonPrimitive?.content ?: error("missing blobKey")
            val destination = Path.of(params["destination"]?.jsonPrimitive?.content ?: error("missing destination"))
            // Poll ownFiles: shareFile writes _ownFiles under a lock, but a concurrent refreshOwn()
            // can briefly clobber it — retry rather than read a single racy snapshot.
            val sharedFile = run {
                repeat(20) {
                    fileShareRepo.ownFiles.value?.files?.firstOrNull { it.blobKey == blobKey }?.let { return@run it }
                    delay(100)
                }
                null
            } ?: error("blobKey_not_found: $blobKey")
            val sources = orderBlobDownloadSources(
                connectorRefs = sharedFile.connectorRefs,
                activeConnectorIds = activeConnectors.value.map { it.identifier },
                runningConnectorIds = runningConnectors.value.map { it.identifier },
            )
            val result = fileShareRepo.downloader.download(
                ownerDeviceId = ownerDeviceId,
                moduleId = ModuleIds.FILES,
                blobKey = blobKey,
                sources = sources,
                expectedChecksumHex = sharedFile.checksum,
                destinationFile = destination,
            )
            buildJsonObject {
                put("result", JsonPrimitive(result::class.simpleName ?: "Unknown"))
                if (result is BlobDownloader.Result.Ok) {
                    put("servedBy", JsonPrimitive(result.source.idString))
                    put("sizeBytes", JsonPrimitive(result.sizeBytes))
                } else {
                    put("servedBy", JsonNull)
                }
            }
        }
    }

    /**
     * Read a module for [deviceId] through [moduleReader] and shape the [ModuleReader.Result] into
     * the `module.read` response: `{state, module}` on Ok, `{state}` on NotFound, `{state, message}`
     * on Error. Generic so each module type keeps its own serializer for the round-trip back to JSON.
     */
    private suspend fun <T> readModuleAsJson(
        moduleId: ModuleId,
        deviceId: DeviceId,
        serializer: KSerializer<T>,
    ): JsonObject = when (val result = moduleReader.read(moduleId, deviceId, serializer)) {
        is ModuleReader.Result.Ok -> buildJsonObject {
            put("state", JsonPrimitive("Ok"))
            put("module", Serialization.json.encodeToJsonElement(serializer, result.value))
        }
        ModuleReader.Result.NotFound -> buildJsonObject { put("state", JsonPrimitive("NotFound")) }
        is ModuleReader.Result.Error -> buildJsonObject {
            put("state", JsonPrimitive("Error"))
            put("message", JsonPrimitive(result.cause.message ?: result.cause::class.simpleName ?: "error"))
        }
    }

    /**
     * Tear down [connectorId]'s session. The server call and local cleanup are isolated so a
     * local mishap after a confirmed server delete (file lock, navigator state, etc.) does not
     * get reported as a network failure — `deleteDevice()` succeeding is the commit point. If
     * the server call itself fails we keep local state untouched and surface
     * [UnlinkResult.NetworkError] so the user can retry. Returns [UnlinkResult.NotLinked] when
     * the connector is unknown.
     */
    suspend fun unlink(connectorId: ConnectorId): UnlinkResult {
        val connector = _activeConnectors.value.firstOrNull { it.identifier == connectorId }
            ?: return UnlinkResult.NotLinked
        try {
            connector.client.deleteDevice(deviceId)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (cause: Throwable) {
            log(TAG, Logging.Priority.WARN, cause) { "Unlink server call failed; keeping local state" }
            return UnlinkResult.NetworkError(cause)
        }
        // Past this point the server already removed the device. Closing the client and the
        // navigation step are best-effort — they don't affect on-disk durability — but
        // credentialsStore.clear() + settings.update are the durable local-unlink commits: if
        // either fails, the next app launch will reload stale state and try to talk to a
        // deleted server-side device. Surface that distinctly via LocalCleanupFailed so the UI
        // can warn loudly.
        log(TAG, Logging.Priority.INFO) {
            "Server DELETE /v1/devices/{self} succeeded for connector=${connectorId.idString}; clearing local state"
        }
        runCatching { connector.close() }
            .onFailure { log(TAG, Logging.Priority.WARN, it) { "Client close failed after successful unlink" } }
        _activeConnectors.update { existing -> existing.filterNot { it.identifier == connectorId } }
        val clearOutcome = runCatching { credentialsStore.clear(connectorId) }
        clearOutcome.onFailure { cause ->
            log(TAG, Logging.Priority.ERROR, cause) {
                "Credentials clear FAILED after successful server delete — local state is now stale"
            }
        }
        val settingsOutcome = runCatching {
            settings.update { current ->
                current.copy(connectors = current.connectors - connectorId.idString)
            }
        }
        settingsOutcome.onFailure { cause ->
            log(TAG, Logging.Priority.ERROR, cause) {
                "Settings.connectors update FAILED after successful server delete — discovery entry is stale"
            }
        }
        if (_activeConnectors.value.isEmpty()) {
            runCatching { navigator.navigateTo(Screen.Linking, clearStack = true) }
                .onFailure { log(TAG, Logging.Priority.WARN, it) { "Navigation to Linking failed after successful unlink" } }
        }
        val firstFailure = clearOutcome.exceptionOrNull() ?: settingsOutcome.exceptionOrNull()
        return if (firstFailure == null) UnlinkResult.Success
        else UnlinkResult.LocalCleanupFailed(firstFailure)
    }

    private fun MutableStateFlow<List<OctiServerConnector>>.update(
        transform: (List<OctiServerConnector>) -> List<OctiServerConnector>,
    ) {
        value = transform(value)
    }

    companion object {

        /**
         * One-shot factory. [passphrasePrompt] is invoked only if the OS keystore is unavailable
         * and we need to fall back to the passphrase-derived AEAD; on the typical macOS/Windows
         * desktop it's never called.
         */
        fun create(passphrasePrompt: () -> CharArray): AppGraph {
            Logging.install()
            val appScope = AppScope()
            val settings = Settings.load()
            val keystore = KeystoreFactory.create(passphrasePrompt)
            val credentialsStore = CredentialsStore(keystore)

            val storedDeviceId = settings.data.deviceId
                ?: error("Settings.load() must have minted a deviceId on first launch")
            val deviceId = DeviceId(storedDeviceId)

            val linkController = LinkController(
                // Rebuild on every link/create attempt — user may have edited the device label
                // on the Linking screen between attempts. Reading from `settings.data` here gives
                // the current snapshot at submit time, not a frozen snapshot from app start.
                deviceMetadataProvider = {
                    DeviceMetadataProvider.current(userLabel = settings.data.deviceLabel)
                },
                credentialsStore = credentialsStore,
                settings = settings,
            )

            // Load all configured connectors from the discovery index. Each must have a matching
            // keystore entry — if it doesn't (race with a partially-rolled-back link, manual
            // keystore wipe, etc.) we drop it from the in-memory list AND clean up the orphan
            // settings entry so the next save round doesn't carry a ghost.
            val initialConnectors = mutableListOf<OctiServerConnector>()
            val orphanIds = mutableListOf<ConnectorId>()
            val metadata = DeviceMetadataProvider.current(userLabel = settings.data.deviceLabel)
            for (config in settings.data.connectors.values) {
                val creds = credentialsStore.load(config.connectorId)
                if (creds == null) {
                    log(TAG, Logging.Priority.WARN) {
                        "settings.connectors lists ${config.connectorId.idString} but keystore has no entry — dropping orphan"
                    }
                    orphanIds += config.connectorId
                    continue
                }
                initialConnectors += OctiServerConnector.fromCredentials(
                    deviceId = deviceId,
                    deviceMetadata = metadata,
                    credentials = creds,
                )
            }
            if (orphanIds.isNotEmpty()) {
                runCatching {
                    settings.update { current ->
                        current.copy(connectors = current.connectors - orphanIds.map { it.idString }.toSet())
                    }
                }.onFailure {
                    log(TAG, Logging.Priority.WARN, it) {
                        "Failed to prune orphan connector entries from settings; will retry next boot"
                    }
                }
            }

            val startScreen = if (initialConnectors.isNotEmpty()) Screen.Dashboard else Screen.Linking
            val navigator = Navigator(initial = startScreen)

            val graph = AppGraph(
                appScope = appScope,
                settings = settings,
                keystore = keystore,
                credentialsStore = credentialsStore,
                deviceId = deviceId,
                linkController = linkController,
                navigator = navigator,
                initialConnectors = initialConnectors,
            )
            graph.registerDebugActions()
            return graph
        }
    }
}

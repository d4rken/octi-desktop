package eu.darken.octi.desktop.di

import eu.darken.octi.desktop.common.coroutine.AppScope
import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.debug.rpc.DebugActionRegistry
import eu.darken.octi.desktop.linking.CredentialsStore
import eu.darken.octi.desktop.linking.LinkController
import eu.darken.octi.desktop.linking.LinkResult
import eu.darken.octi.desktop.linking.UnlinkResult
import eu.darken.octi.desktop.modules.clipboard.ClipboardSync
import eu.darken.octi.desktop.modules.files.FileShareRepo
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import eu.darken.octi.desktop.modules.meta.MetaWriter
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.storage.Settings
import eu.darken.octi.desktop.storage.keystore.Keystore
import eu.darken.octi.desktop.storage.keystore.KeystoreFactory
import eu.darken.octi.desktop.sync.DeviceListRepo
import eu.darken.octi.desktop.sync.ModuleReader
import eu.darken.octi.desktop.sync.OctiServerWebSocketClient
import eu.darken.octi.desktop.sync.SyncEventBus
import eu.darken.octi.desktop.ui.dashboard.DashboardModuleRepo
import eu.darken.octi.desktop.ui.nav.Navigator
import eu.darken.octi.desktop.ui.nav.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * Multi-connector ready: [activeConnectors] is a list, [primaryConnector] is a derived helper
 * for the (today 0-or-1) common case. When a second connector type lands (GDrive) only the
 * sync-layer collectors that iterate connectors change shape; the list-based plumbing is
 * already in place.
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
     * Convenience for the common case of "the only OctiServer connector". Today every consumer
     * reads this; the explicit `firstOrNull` semantics keep the single-vs-many distinction
     * visible in the type. When the desktop ever surfaces a multi-account UI, consumers move
     * to [activeConnectors] iteration.
     */
    val primaryConnector: StateFlow<OctiServerConnector?> = activeConnectors
        .map { it.firstOrNull() }
        .stateIn(appScope, SharingStarted.Eagerly, initialConnectors.firstOrNull())

    /**
     * Created eagerly so its [activeConnectors] collector is wired before any UI subscribes.
     * Constructed lazily — the constructor reads [activeConnectors], which must be initialized
     * first. Property-order matters: keep these below the [_activeConnectors] field.
     */
    val syncEventBus: SyncEventBus = SyncEventBus()
    val deviceListRepo: DeviceListRepo by lazy { DeviceListRepo(this) }
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
        navigator.navigateTo(Screen.Dashboard, clearStack = true)
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
            if (result is LinkResult.Success) onLinked(result.connectorId, result.credentials)
            buildJsonObject {
                put("result", JsonPrimitive(result::class.simpleName ?: "Unknown"))
            }
        }

        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "account.unlink",
                description = "Unlink the primary connector: calls DELETE /v1/devices/{self} then " +
                    "clears local credentials + settings entry. Returns the UnlinkResult variant " +
                    "name so callers can branch on Success / NetworkError.",
            ),
        ) {
            val target = primaryConnector.value?.identifier
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

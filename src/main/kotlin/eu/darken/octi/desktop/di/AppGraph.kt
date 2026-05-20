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
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - Pre-link: [activeClient] is null, navigator starts at [Screen.Linking].
 * - Post-link: [activeClient] is non-null; navigator starts at [Screen.Dashboard].
 *
 * Linking and unlinking flip [activeClient] via [onLinked] / [unlink]. The current client owns
 * its own Ktor HttpClient; we close + replace it on each transition rather than mutating
 * config in place (avoids stale auth state on the Ktor connection pool).
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
    private val initialCredentials: OctiServer.Credentials?,
) {

    private val _activeClient = MutableStateFlow<OctiServerHttpClient?>(null)
    val activeClient: StateFlow<OctiServerHttpClient?> = _activeClient.asStateFlow()

    /**
     * Created eagerly so its `activeClient.flatMapLatest` collector is wired before any UI
     * subscribes. Constructed lazily — the constructor reads [activeClient], which must be
     * initialized first. Property-order matters: keep this below the [_activeClient] field.
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
        if (initialCredentials != null) {
            _activeClient.value = buildClient(initialCredentials)
            log(TAG, Logging.Priority.INFO) {
                "Booted with stored credentials for account=${initialCredentials.accountId.id.take(8)}... " +
                    "keysetMode=${initialCredentials.encryptionKeyset.type}"
            }
        } else {
            log(TAG, Logging.Priority.INFO) { "No stored credentials; starting in Linking flow" }
        }
    }

    /** Called by the Link flow on [LinkResult.Success] to swap in a fresh authenticated client. */
    fun onLinked() {
        val credentials = credentialsStore.load()
        if (credentials == null) {
            log(TAG, Logging.Priority.WARN) { "onLinked() called but no credentials found in store" }
            return
        }
        _activeClient.value?.close()
        _activeClient.value = buildClient(credentials)
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
            if (result == LinkResult.Success) onLinked()
            buildJsonObject {
                put("result", JsonPrimitive(result::class.simpleName ?: "Unknown"))
            }
        }

        debugActions.registerUiAction(
            DebugActionRegistry.Metadata(
                name = "account.unlink",
                description = "Unlink: calls DELETE /v1/devices/{self} then clears local credentials. " +
                    "Returns the UnlinkResult variant name so callers can branch on Success / NetworkError.",
            ),
        ) {
            val result = unlink()
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
    }

    /**
     * Tear down the active session. The server call and local cleanup are isolated so a local
     * mishap after a confirmed server delete (file lock, navigator state, etc.) does not get
     * reported as a network failure — `deleteDevice()` succeeding is the commit point. If the
     * server call itself fails we keep local state untouched and surface
     * [UnlinkResult.NetworkError] so the user can retry. Returns [UnlinkResult.NotLinked] when
     * nothing is linked.
     */
    suspend fun unlink(): UnlinkResult {
        val active = _activeClient.value ?: return UnlinkResult.NotLinked
        try {
            active.deleteDevice(deviceId)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (cause: Throwable) {
            log(TAG, Logging.Priority.WARN, cause) { "Unlink server call failed; keeping local credentials" }
            return UnlinkResult.NetworkError(cause)
        }
        // Past this point the server already removed the device. Closing the client and the
        // navigation step are best-effort — they don't affect on-disk durability — but
        // credentialsStore.clear() is the durable local-unlink commit: if it fails, the next
        // app launch will reload stale credentials and try to talk to a deleted server-side
        // device. Surface that distinctly via LocalCleanupFailed so the UI can warn loudly.
        log(TAG, Logging.Priority.INFO) { "Server DELETE /v1/devices/{self} succeeded; clearing local state" }
        runCatching { active.close() }
            .onFailure { log(TAG, Logging.Priority.WARN, it) { "Client close failed after successful unlink" } }
        _activeClient.value = null
        val clearOutcome = runCatching { credentialsStore.clear() }
        clearOutcome.onFailure { cause ->
            log(TAG, Logging.Priority.ERROR, cause) {
                "Credentials clear FAILED after successful server delete — local state is now stale"
            }
        }
        runCatching { navigator.navigateTo(Screen.Linking, clearStack = true) }
            .onFailure { log(TAG, Logging.Priority.WARN, it) { "Navigation to Linking failed after successful unlink" } }
        return clearOutcome.fold(
            onSuccess = { UnlinkResult.Success },
            onFailure = { UnlinkResult.LocalCleanupFailed(it) },
        )
    }

    private fun buildClient(credentials: OctiServer.Credentials): OctiServerHttpClient {
        val metadata = DeviceMetadataProvider.current(userLabel = settings.data.deviceLabel)
        return OctiServerHttpClient(
            address = credentials.serverAdress,
            deviceId = deviceId,
            deviceMetadata = metadata,
            credentials = credentials,
        )
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
            )

            val storedCredentials = credentialsStore.load()
            val startScreen = if (storedCredentials != null) Screen.Dashboard else Screen.Linking
            val navigator = Navigator(initial = startScreen)

            val graph = AppGraph(
                appScope = appScope,
                settings = settings,
                keystore = keystore,
                credentialsStore = credentialsStore,
                deviceId = deviceId,
                linkController = linkController,
                navigator = navigator,
                initialCredentials = storedCredentials,
            )
            graph.registerDebugActions()
            return graph
        }
    }
}

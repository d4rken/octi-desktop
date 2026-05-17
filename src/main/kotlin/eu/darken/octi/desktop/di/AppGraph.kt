package eu.darken.octi.desktop.di

import eu.darken.octi.desktop.common.coroutine.AppScope
import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.linking.CredentialsStore
import eu.darken.octi.desktop.linking.LinkController
import eu.darken.octi.desktop.linking.LinkResult
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
import eu.darken.octi.desktop.ui.nav.Navigator
import eu.darken.octi.desktop.ui.nav.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Tear down the active session locally — does NOT call the server's DELETE /v1/devices. */
    fun unlink() {
        _activeClient.value?.close()
        _activeClient.value = null
        credentialsStore.clear()
        navigator.navigateTo(Screen.Linking, clearStack = true)
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

            val deviceMetadata = DeviceMetadataProvider.current(userLabel = settings.data.deviceLabel)
            val linkController = LinkController(
                deviceMetadata = deviceMetadata,
                credentialsStore = credentialsStore,
            )

            val storedCredentials = credentialsStore.load()
            val startScreen = if (storedCredentials != null) Screen.Dashboard else Screen.Linking
            val navigator = Navigator(initial = startScreen)

            return AppGraph(
                appScope = appScope,
                settings = settings,
                keystore = keystore,
                credentialsStore = credentialsStore,
                deviceId = deviceId,
                linkController = linkController,
                navigator = navigator,
                initialCredentials = storedCredentials,
            )
        }
    }
}

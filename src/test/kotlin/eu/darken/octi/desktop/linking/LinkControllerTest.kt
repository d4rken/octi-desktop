package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.LinkingData
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector.Companion.toConnectorId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpException
import eu.darken.octi.desktop.protocol.octiserver.dto.RegisterResponse
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.storage.Settings
import eu.darken.octi.desktop.storage.keystore.Keystore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class LinkControllerTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Serialization.json
    private val deviceMetadata = DeviceMetadata(
        version = "0.0.1-test",
        platform = "desktop-test",
        label = "test-host",
    )
    private val metadataProvider: () -> DeviceMetadata = { deviceMetadata }
    private val newDeviceId = DeviceId("11111111-2222-3333-4444-555555555555")

    /** Factory that throws if invoked. Use when a test must prove the network was never touched. */
    private val rejectingFactory = LinkController.HttpClientFactory { _, _, _, _ ->
        error("HttpClientFactory must not be invoked when local validation fails")
    }

    private fun newKeystore(): Keystore = object : Keystore {
        private val storage = mutableMapOf<String, ByteArray>()
        override val backendDescription: String = "test"
        override fun store(key: String, value: ByteArray) {
            storage[key] = value
        }
        override fun load(key: String): ByteArray? = storage[key]
        override fun delete(key: String) {
            storage.remove(key)
        }
    }

    private fun failingKeystore(failureMessage: String): Keystore = object : Keystore {
        override val backendDescription = "test-fail"
        override fun store(key: String, value: ByteArray) = throw RuntimeException(failureMessage)
        override fun load(key: String): ByteArray? = null
        override fun delete(key: String) = Unit
    }

    /** Per-test settings backed by a tmp file. Each call gets a fresh file. */
    private fun newSettings(name: String = "settings.json"): Settings =
        Settings.load(file = tempDir.resolve(name))

    private fun newController(
        store: CredentialsStore = CredentialsStore(newKeystore()),
        factory: LinkController.HttpClientFactory = rejectingFactory,
        gcmSivAvailable: () -> Boolean = { true },
        settings: Settings = newSettings(),
    ): LinkController = LinkController(
        deviceMetadataProvider = metadataProvider,
        credentialsStore = store,
        settings = settings,
        httpClientFactory = factory,
        gcmSivAvailable = gcmSivAvailable,
    )

    /** Build a real LinkingData with a real Tink keyset and encode it. */
    private fun validEncodedLink(): String {
        val keyset = PayloadEncryption().exportKeyset()
        return LinkingData(
            serverAdress = OctiServer.Address(domain = "test.example.com"),
            linkCode = OctiServer.Credentials.LinkCode("share-abc"),
            encryptionKeyset = keyset,
        ).toEncodedString(json)
    }

    // --- Local validation (factory never invoked) ---

    @Test
    @DisplayName("Empty input → InvalidBase64, factory never invoked")
    fun emptyInput() = runTest {
        newController().link("", newDeviceId) shouldBe LinkResult.InvalidBase64
    }

    @Test
    @DisplayName("Non-base64 garbage → InvalidBase64, factory never invoked")
    fun invalidBase64() = runTest {
        newController().link("%%% not base64 %%%", newDeviceId) shouldBe LinkResult.InvalidBase64
    }

    @Test
    @DisplayName("Valid base64, bad gzip stream → InvalidGzip, factory never invoked")
    fun invalidGzip() = runTest {
        val encoded = "hello world".encodeUtf8().base64()
        newController().link(encoded, newDeviceId) shouldBe LinkResult.InvalidGzip
    }

    @Test
    @DisplayName("Valid base64+gzip but wrong JSON shape → InvalidJson, factory never invoked")
    fun invalidJsonShape() = runTest {
        val gzipped = """{"unrelated": "shape"}""".encodeUtf8().toGzip().base64()
        val result = newController().link(gzipped, newDeviceId)
        result.shouldBeInstanceOf<LinkResult.InvalidJson>()
    }

    @Test
    @DisplayName("Valid LinkingData JSON but Tink rejects the keyset bytes → InvalidKeyset, factory never invoked")
    fun invalidKeyset() = runTest {
        val badKeyset = PayloadEncryption.KeySet(
            type = "AES256_GCM_SIV",
            key = "definitely-not-a-real-tink-keyset".encodeUtf8(),
        )
        val link = LinkingData(
            serverAdress = OctiServer.Address(domain = "test.example.com"),
            linkCode = OctiServer.Credentials.LinkCode("share-abc"),
            encryptionKeyset = badKeyset,
        )
        val encoded = link.toEncodedString(json)
        val result = newController().link(encoded, newDeviceId)
        result.shouldBeInstanceOf<LinkResult.InvalidKeyset>()
    }

    // --- Server-stage paths for link() ---

    @Test
    @DisplayName("link happy path: server returns credentials → keystore + settings persisted → Success(connectorId, credentials)")
    fun linkHappyPath() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-123",
            password = "pw-shhh",
        )

        val store = CredentialsStore(newKeystore())
        val settings = newSettings()
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory, settings = settings)

        val result = controller.link(validEncodedLink(), newDeviceId)
        result.shouldBeInstanceOf<LinkResult.Success>()
        result.credentials.accountId.id shouldBe "acct-123"
        result.credentials.devicePassword.password shouldBe "pw-shhh"
        result.connectorId shouldBe result.credentials.toConnectorId()
        // Keystore contains the credentials under the connector id.
        val loaded = store.load(result.connectorId)
        check(loaded != null) { "credentials must be persisted under the connectorId" }
        loaded.accountId.id shouldBe "acct-123"
        // Settings.connectors has the matching discovery entry.
        val cfg = settings.data.connectors[result.connectorId.idString]
        check(cfg != null) { "settings.connectors must contain the new connector entry" }
        cfg.connectorId shouldBe result.connectorId
        coVerify { client.register(shareCode = "share-abc") }
        verify { client.close() }
    }

    @Test
    @DisplayName("link: expired share code (404) → ShareCodeExpiredOrConsumed, client closed, no credentials, no settings entry")
    fun expiredShareCode() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = any()) } throws OctiServerHttpException(HttpStatusCode.NotFound, "test")

        val store = CredentialsStore(newKeystore())
        val settings = newSettings()
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory, settings = settings)

        controller.link(validEncodedLink(), newDeviceId) shouldBe LinkResult.ShareCodeExpiredOrConsumed
        settings.data.connectors shouldBe emptyMap()
        verify { client.close() }
    }

    @Test
    @DisplayName("link: keystore write fails → rollback DELETE called → KeystoreFailureRolledBack; settings untouched")
    fun keystoreFailureTriggersRollback() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-456",
            password = "pw-789",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        justRun { authedClient.close() }
        coEvery { authedClient.deleteDevice(any()) } returns Unit

        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, credentials ->
            when (callIndex.getAndIncrement()) {
                0 -> {
                    check(credentials == null) { "first factory call must be unauthed" }
                    unauthedClient
                }
                1 -> {
                    check(credentials != null) { "rollback factory call must carry credentials" }
                    credentials.accountId.id shouldBe "acct-456"
                    authedClient
                }
                else -> error("factory called more than twice")
            }
        }

        val settings = newSettings()
        val controller = newController(
            store = CredentialsStore(failingKeystore("disk full")),
            factory = factory,
            settings = settings,
        )
        val result = controller.link(validEncodedLink(), newDeviceId)
        result.shouldBeInstanceOf<LinkResult.KeystoreFailureRolledBack>()
        settings.data.connectors shouldBe emptyMap()
        coVerify(exactly = 1) { authedClient.deleteDevice(newDeviceId) }
        callIndex.get() shouldBe 2
        verify { unauthedClient.close() }
        verify { authedClient.close() }
    }

    @Test
    @DisplayName("link: keystore fails AND rollback DELETE also fails → OrphanedDevice")
    fun keystoreFailureRollbackAlsoFails() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-xyz",
            password = "pw-xyz",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { authedClient.deleteDevice(any()) } throws RuntimeException("server unreachable")

        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, _ ->
            if (callIndex.getAndIncrement() == 0) unauthedClient else authedClient
        }

        val controller = newController(
            store = CredentialsStore(failingKeystore("permission denied")),
            factory = factory,
        )
        val result = controller.link(validEncodedLink(), newDeviceId)
        result.shouldBeInstanceOf<LinkResult.OrphanedDevice>()
        verify { authedClient.close() }
    }

    @Test
    @DisplayName("link: settings write fails after keystore save → keystore cleared + rollback DELETE → SettingsPersistFailedRolledBack")
    fun settingsFailureTriggersFullRollback() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-settings",
            password = "pw-settings",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { authedClient.deleteDevice(any()) } returns Unit

        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, credentials ->
            when (callIndex.getAndIncrement()) {
                0 -> {
                    check(credentials == null)
                    unauthedClient
                }
                1 -> {
                    check(credentials != null) { "rollback factory call must carry credentials" }
                    credentials.accountId.id shouldBe "acct-settings"
                    authedClient
                }
                else -> error("factory called more than twice")
            }
        }

        // Settings file path that can't be written to — points at a directory rather than a
        // file. The atomic write step throws on rename, which the controller maps to
        // SettingsPersistFailedRolledBack.
        val settingsDir = tempDir.resolve("settings-broken")
        // Initialize Settings against a real path first so the deviceId is minted, then sabotage
        // the file by replacing it with a directory of the same name.
        val sabotagedSettings = Settings.load(file = settingsDir.resolve("settings.json"))
        // Replace the settings file with a directory so subsequent atomic writes fail.
        java.nio.file.Files.delete(settingsDir.resolve("settings.json"))
        java.nio.file.Files.createDirectory(settingsDir.resolve("settings.json"))

        val store = CredentialsStore(newKeystore())
        val controller = newController(store = store, factory = factory, settings = sabotagedSettings)
        val result = controller.link(validEncodedLink(), newDeviceId)
        result.shouldBeInstanceOf<LinkResult.SettingsPersistFailedRolledBack>()
        coVerify(exactly = 1) { authedClient.deleteDevice(newDeviceId) }
        // Keystore was cleared as part of rollback — credentials for the new id are gone.
        val connectorId = OctiServer.Credentials(
            serverAdress = OctiServer.Address(domain = "test.example.com"),
            accountId = OctiServer.Credentials.AccountId("acct-settings"),
            devicePassword = OctiServer.Credentials.DevicePassword("pw-settings"),
            encryptionKeyset = PayloadEncryption().exportKeyset(),
        ).toConnectorId()
        // Can't easily reconstruct the exact connectorId (keyset is fresh per test run) — but
        // the keystore should be empty of any credential entry under any id derived from the
        // server domain in validEncodedLink().
        store.load(connectorId) shouldBe null
        verify { authedClient.close() }
    }

    // --- createAccount() ---

    @Test
    @DisplayName("createAccount happy path: credentials persisted in keystore + settings.connectors, Success carries connectorId")
    fun createAccountHappyPath() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = null) } returns RegisterResponse(
            accountID = "acct-new-1",
            password = "fresh-pw",
        )

        val store = CredentialsStore(newKeystore())
        val settings = newSettings()
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory, settings = settings, gcmSivAvailable = { true })

        val target = OctiServer.Address(domain = "fresh.example.com")
        val result = controller.createAccount(newDeviceId, target)
        result.shouldBeInstanceOf<CreateAccountResult.Success>()
        result.credentials.accountId.id shouldBe "acct-new-1"
        result.credentials.serverAdress shouldBe target
        result.credentials.encryptionKeyset.type shouldBe "AES256_GCM_SIV"
        result.connectorId shouldBe result.credentials.toConnectorId()
        val loaded = store.load(result.connectorId)
        check(loaded != null) { "credentials must be persisted under the connectorId" }
        loaded.accountId.id shouldBe "acct-new-1"
        loaded.serverAdress shouldBe target
        settings.data.connectors[result.connectorId.idString]?.connectorId shouldBe result.connectorId
        coVerify { client.register(shareCode = null) }
        verify { client.close() }
    }

    @Test
    @DisplayName("createAccount: server 400 → DeviceAlreadyRegistered, no keystore/settings writes, client closed")
    fun createAccountDeviceAlreadyRegistered() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = null) } throws
            OctiServerHttpException(HttpStatusCode.BadRequest, "test")

        val store = CredentialsStore(newKeystore())
        val settings = newSettings()
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory, settings = settings)

        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result shouldBe CreateAccountResult.DeviceAlreadyRegistered
        settings.data.connectors shouldBe emptyMap()
        verify { client.close() }
    }

    @Test
    @DisplayName("createAccount: non-400 server error → NetworkError, no keystore/settings writes, client closed")
    fun createAccountNetworkError() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = null) } throws RuntimeException("connect timed out")

        val store = CredentialsStore(newKeystore())
        val settings = newSettings()
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory, settings = settings)

        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result.shouldBeInstanceOf<CreateAccountResult.NetworkError>()
        settings.data.connectors shouldBe emptyMap()
        verify { client.close() }
    }

    @Test
    @DisplayName("createAccount: SIV fallback when GCM-SIV unavailable → keyset type is AES256_SIV")
    fun createAccountSivFallback() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = null) } returns RegisterResponse(
            accountID = "acct-siv",
            password = "siv-pw",
        )
        val store = CredentialsStore(newKeystore())
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory, gcmSivAvailable = { false })

        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result.shouldBeInstanceOf<CreateAccountResult.Success>()
        result.credentials.encryptionKeyset.type shouldBe "AES256_SIV"
    }

    @Test
    @DisplayName("createAccount: keystore save fails → rollback via deleteAccount → KeystoreFailureRolledBack")
    fun createAccountKeystoreRollback() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = null) } returns RegisterResponse(
            accountID = "acct-roll",
            password = "pw-roll",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { authedClient.deleteAccount() } returns Unit

        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, credentials ->
            when (callIndex.getAndIncrement()) {
                0 -> {
                    check(credentials == null)
                    unauthedClient
                }
                1 -> {
                    check(credentials != null) { "rollback factory call must carry credentials" }
                    credentials.accountId.id shouldBe "acct-roll"
                    authedClient
                }
                else -> error("factory called more than twice")
            }
        }

        val settings = newSettings()
        val controller = newController(
            store = CredentialsStore(failingKeystore("disk full")),
            factory = factory,
            settings = settings,
        )
        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result.shouldBeInstanceOf<CreateAccountResult.KeystoreFailureRolledBack>()
        settings.data.connectors shouldBe emptyMap()
        // Must call deleteAccount (not deleteDevice) — fresh account has no other devices.
        coVerify(exactly = 1) { authedClient.deleteAccount() }
        coVerify(exactly = 0) { authedClient.deleteDevice(any()) }
        callIndex.get() shouldBe 2
        verify { unauthedClient.close() }
        verify { authedClient.close() }
    }

    @Test
    @DisplayName("createAccount: keystore fails AND rollback deleteAccount fails → OrphanedAccount")
    fun createAccountKeystoreAndRollbackFail() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = null) } returns RegisterResponse(
            accountID = "acct-orphan",
            password = "pw-orphan",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { authedClient.deleteAccount() } throws RuntimeException("server unreachable")

        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, _ ->
            if (callIndex.getAndIncrement() == 0) unauthedClient else authedClient
        }

        val controller = newController(
            store = CredentialsStore(failingKeystore("permission denied")),
            factory = factory,
        )
        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result.shouldBeInstanceOf<CreateAccountResult.OrphanedAccount>()
        verify { authedClient.close() }
    }

    @Test
    @DisplayName("createAccount: settings write fails after keystore save → keystore cleared + rollback deleteAccount → SettingsPersistFailedRolledBack")
    fun createAccountSettingsFailureTriggersFullRollback() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = null) } returns RegisterResponse(
            accountID = "acct-set",
            password = "pw-set",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { authedClient.deleteAccount() } returns Unit

        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, _ ->
            if (callIndex.getAndIncrement() == 0) unauthedClient else authedClient
        }

        val settingsDir = tempDir.resolve("settings-broken-create")
        val sabotagedSettings = Settings.load(file = settingsDir.resolve("settings.json"))
        java.nio.file.Files.delete(settingsDir.resolve("settings.json"))
        java.nio.file.Files.createDirectory(settingsDir.resolve("settings.json"))

        val store = CredentialsStore(newKeystore())
        val controller = newController(store = store, factory = factory, settings = sabotagedSettings)
        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result.shouldBeInstanceOf<CreateAccountResult.SettingsPersistFailedRolledBack>()
        coVerify(exactly = 1) { authedClient.deleteAccount() }
        verify { authedClient.close() }
    }
}

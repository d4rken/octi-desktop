package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.LinkingData
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpException
import eu.darken.octi.desktop.protocol.octiserver.dto.RegisterResponse
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
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
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LinkControllerTest {

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

    private fun newController(
        store: CredentialsStore = CredentialsStore(newKeystore()),
        factory: LinkController.HttpClientFactory = rejectingFactory,
        gcmSivAvailable: () -> Boolean = { true },
    ): LinkController = LinkController(
        deviceMetadataProvider = metadataProvider,
        credentialsStore = store,
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
    @DisplayName("link happy path: server returns credentials → save → Success; client closed")
    fun linkHappyPath() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-123",
            password = "pw-shhh",
        )

        val store = CredentialsStore(newKeystore())
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory)

        val result = controller.link(validEncodedLink(), newDeviceId)
        result shouldBe LinkResult.Success
        val loaded = store.load()
        check(loaded != null) { "credentials must be persisted" }
        loaded.accountId.id shouldBe "acct-123"
        loaded.devicePassword.password shouldBe "pw-shhh"
        coVerify { client.register(shareCode = "share-abc") }
        verify { client.close() }
    }

    @Test
    @DisplayName("link: expired share code (404) → ShareCodeExpiredOrConsumed, client closed, no credentials")
    fun expiredShareCode() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = any()) } throws OctiServerHttpException(HttpStatusCode.NotFound, "test")

        val store = CredentialsStore(newKeystore())
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory)

        controller.link(validEncodedLink(), newDeviceId) shouldBe LinkResult.ShareCodeExpiredOrConsumed
        check(store.load() == null) { "no credentials should be saved on expired share code" }
        // Even when register() throws, the client must be closed (avoids the prior leak).
        verify { client.close() }
    }

    @Test
    @DisplayName("link: keystore write fails → rollback DELETE called → KeystoreFailureRolledBack")
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

        val controller = newController(
            store = CredentialsStore(failingKeystore("disk full")),
            factory = factory,
        )
        val result = controller.link(validEncodedLink(), newDeviceId)
        result.shouldBeInstanceOf<LinkResult.KeystoreFailureRolledBack>()
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

    // --- createAccount() ---

    @Test
    @DisplayName("createAccount happy path: server returns credentials → save → Success with GCM-SIV keyset")
    fun createAccountHappyPath() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = null) } returns RegisterResponse(
            accountID = "acct-new-1",
            password = "fresh-pw",
        )

        val store = CredentialsStore(newKeystore())
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory, gcmSivAvailable = { true })

        val target = OctiServer.Address(domain = "fresh.example.com")
        val result = controller.createAccount(newDeviceId, target)
        result shouldBe CreateAccountResult.Success
        val loaded = store.load()
        check(loaded != null) { "credentials must be persisted" }
        loaded.accountId.id shouldBe "acct-new-1"
        loaded.devicePassword.password shouldBe "fresh-pw"
        loaded.serverAdress shouldBe target
        loaded.encryptionKeyset.type shouldBe "AES256_GCM_SIV"
        coVerify { client.register(shareCode = null) }
        verify { client.close() }
    }

    @Test
    @DisplayName("createAccount: server 400 → DeviceAlreadyRegistered, no credentials saved, client closed")
    fun createAccountDeviceAlreadyRegistered() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = null) } throws
            OctiServerHttpException(HttpStatusCode.BadRequest, "test")

        val store = CredentialsStore(newKeystore())
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory)

        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result shouldBe CreateAccountResult.DeviceAlreadyRegistered
        check(store.load() == null)
        verify { client.close() }
    }

    @Test
    @DisplayName("createAccount: non-400 server error → NetworkError, no credentials saved, client closed")
    fun createAccountNetworkError() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = null) } throws RuntimeException("connect timed out")

        val store = CredentialsStore(newKeystore())
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = newController(store, factory)

        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result.shouldBeInstanceOf<CreateAccountResult.NetworkError>()
        check(store.load() == null)
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

        controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com")) shouldBe
            CreateAccountResult.Success
        store.load()?.encryptionKeyset?.type shouldBe "AES256_SIV"
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

        val controller = newController(
            store = CredentialsStore(failingKeystore("disk full")),
            factory = factory,
        )
        val result = controller.createAccount(newDeviceId, OctiServer.Address(domain = "x.example.com"))
        result.shouldBeInstanceOf<CreateAccountResult.KeystoreFailureRolledBack>()
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
}

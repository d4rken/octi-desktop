package eu.darken.octi.desktop.blob

import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.encryption.StreamingPayloadCipher
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.protocol.sync.DeviceId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Unit tests for [BlobDownloader]'s dispatcher — the parts of the multi-source flow that
 * decide which candidate to attempt without crossing into the real Tink decrypt + checksum
 * verify path. The full encrypt/decrypt round-trip is exercised by the smoke suite against a
 * real sync-server; mocking it here would require pre-computed ciphertext for each test
 * connector's keyset, which is more brittle than the smoke alternative.
 */
class BlobDownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    private val alpha = ConnectorId(ConnectorType.OCTISERVER, "alpha.example.com", "acct-a")
    private val bravo = ConnectorId(ConnectorType.OCTISERVER, "bravo.example.com", "acct-b")

    private val ownerDeviceId = DeviceId("device-target")

    private fun buildGraph(activeConnectors: List<OctiServerConnector>): AppGraph {
        val flow = MutableStateFlow(activeConnectors)
        return mockk<AppGraph>(relaxed = true).also { graph ->
            every { graph.activeConnectors } returns flow
        }
    }

    private fun mockConnector(id: ConnectorId): OctiServerConnector =
        mockk<OctiServerConnector>(relaxed = true).also {
            every { it.identifier } returns id
        }

    @Test
    fun `empty sources map returns NoClient without touching any connector`() = runTest {
        val graph = buildGraph(activeConnectors = listOf(mockConnector(alpha)))
        val downloader = BlobDownloader(graph)
        val result = downloader.download(
            ownerDeviceId = ownerDeviceId,
            moduleId = ModuleIds.FILES,
            blobKey = "key",
            sources = emptyMap(),
            expectedChecksumHex = "deadbeef",
            destinationFile = tempDir.resolve("out.bin"),
        )
        result shouldBe BlobDownloader.Result.NoClient
    }

    @Test
    fun `sources whose ConnectorIds are not in activeConnectors return NoClient`() = runTest {
        // graph has alpha; sources references bravo (a connector we don't have linked). The
        // downloader should not try to fetch from bravo and should not crash — it returns
        // NoClient because there's no candidate to try.
        val graph = buildGraph(activeConnectors = listOf(mockConnector(alpha)))
        val downloader = BlobDownloader(graph)
        val result = downloader.download(
            ownerDeviceId = ownerDeviceId,
            moduleId = ModuleIds.FILES,
            blobKey = "key",
            sources = mapOf(bravo to "blob-on-bravo"),
            expectedChecksumHex = "deadbeef",
            destinationFile = tempDir.resolve("out.bin"),
        )
        result.shouldBeInstanceOf<BlobDownloader.Result.NoClient>()
    }

    /**
     * The fallthrough the e2e smoke can't reach: when the first candidate's GET throws, the
     * downloader must move on and let the SECOND candidate serve the blob — returning
     * `Ok(source = second)`. Ciphertext is produced at test time with the same keyset + AAD the
     * downloader uses, so it's a real decrypt+checksum success, not a brittle pre-baked vector.
     */
    @Test
    fun `first source erroring falls through to the second which serves the blob`() = runTest {
        val keyset = PayloadEncryption().exportKeyset()
        val blobKey = "blob-key"
        val plaintext = "multi-source fallthrough payload".toByteArray()
        val cipher = StreamingPayloadCipher(keyset)
        val aad = cipher.aadFor(ownerDeviceId.id, ModuleIds.FILES.id, blobKey)
        val ciphertext = Buffer().also { sink ->
            cipher.encrypt(Buffer().write(plaintext), sink, aad)
        }.readByteArray()
        val checksum = sha256Hex(plaintext)

        val alphaClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { alphaClient.getBlobBytes(ModuleIds.FILES, any(), any()) } throws IOException("alpha is down")
        val bravoClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { bravoClient.getBlobBytes(ModuleIds.FILES, any(), any()) } returns ciphertext

        val alphaConn = mockConnector(alpha, keyset, alphaClient)
        val bravoConn = mockConnector(bravo, keyset, bravoClient)

        val graph = buildGraph(activeConnectors = listOf(alphaConn, bravoConn))
        val downloader = BlobDownloader(graph)
        val destination = tempDir.resolve("out.bin")

        // LinkedHashMap iteration order = candidate order: alpha first, then bravo.
        val result = downloader.download(
            ownerDeviceId = ownerDeviceId,
            moduleId = ModuleIds.FILES,
            blobKey = blobKey,
            sources = linkedMapOf(alpha to "blob-on-alpha", bravo to "blob-on-bravo"),
            expectedChecksumHex = checksum,
            destinationFile = destination,
        )

        val ok = result.shouldBeInstanceOf<BlobDownloader.Result.Ok>()
        ok.source shouldBe bravo
        Files.readAllBytes(destination) shouldBe plaintext
    }

    /** Stub a connector with a real keyset (for the in-test cipher) and a pre-stubbed client. */
    private fun mockConnector(
        id: ConnectorId,
        keyset: PayloadEncryption.KeySet,
        client: OctiServerHttpClient,
    ): OctiServerConnector {
        val credentials = mockk<OctiServer.Credentials>(relaxed = true).also {
            every { it.encryptionKeyset } returns keyset
        }
        return mockk<OctiServerConnector>(relaxed = true).also {
            every { it.identifier } returns id
            every { it.credentials } returns credentials
            every { it.client } returns client
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package eu.darken.octi.desktop.blob

import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.protocol.sync.DeviceId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

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
}

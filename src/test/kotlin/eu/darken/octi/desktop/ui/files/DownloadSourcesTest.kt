package eu.darken.octi.desktop.ui.files

import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.protocol.sync.RemoteBlobRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadSourcesTest {

    private val alpha = ConnectorId(ConnectorType.OCTISERVER, "alpha.example.com", "acct-a")
    private val bravo = ConnectorId(ConnectorType.OCTISERVER, "bravo.example.com", "acct-b")
    private val charlie = ConnectorId(ConnectorType.OCTISERVER, "charlie.example.com", "acct-c")

    @Test
    fun `empty connectorRefs returns empty map`() {
        orderBlobDownloadSources(
            connectorRefs = emptyMap(),
            activeConnectorIds = listOf(alpha, bravo),
            runningConnectorIds = listOf(alpha, bravo),
        ) shouldBe emptyMap()
    }

    @Test
    fun `connectorRefs entries for unknown ids are filtered out`() {
        val ghost = ConnectorId(ConnectorType.OCTISERVER, "ghost.example.com", "acct-x")
        val result = orderBlobDownloadSources(
            connectorRefs = mapOf(
                alpha.idString to RemoteBlobRef("blob-a"),
                ghost.idString to RemoteBlobRef("blob-ghost"),
            ),
            activeConnectorIds = listOf(alpha, bravo),
            runningConnectorIds = listOf(alpha, bravo),
        )
        // Only alpha survives — ghost isn't in activeConnectorIds so we can't fetch from it.
        result shouldBe mapOf(alpha to "blob-a")
    }

    @Test
    fun `running connectors come before paused`() {
        // alpha + bravo active, only alpha is running (bravo is paused). Both have the blob.
        // Even though alpha sorts lex-LATER than bravo, it should come first because it's
        // running.
        val refs = mapOf(
            alpha.idString to RemoteBlobRef("blob-a"),
            bravo.idString to RemoteBlobRef("blob-b"),
        )
        val result = orderBlobDownloadSources(
            connectorRefs = refs,
            activeConnectorIds = listOf(alpha, bravo),
            runningConnectorIds = listOf(alpha),
        )
        // alpha first (running), bravo second (paused — still tried as last resort).
        result.keys.toList() shouldBe listOf(alpha, bravo)
        result[alpha] shouldBe "blob-a"
        result[bravo] shouldBe "blob-b"
    }

    @Test
    fun `within running bucket sort lex-smallest idString first`() {
        // All three running. Default Map order on creation would follow connectorRefs map
        // iteration, which is unstable across platforms — assert the actual sorted order.
        val refs = mapOf(
            charlie.idString to RemoteBlobRef("blob-c"),
            alpha.idString to RemoteBlobRef("blob-a"),
            bravo.idString to RemoteBlobRef("blob-b"),
        )
        val result = orderBlobDownloadSources(
            connectorRefs = refs,
            activeConnectorIds = listOf(alpha, bravo, charlie),
            runningConnectorIds = listOf(alpha, bravo, charlie),
        )
        // alpha.idString < bravo.idString < charlie.idString lex-wise.
        result.keys.toList() shouldBe listOf(alpha, bravo, charlie)
    }

    @Test
    fun `all-paused connectors still produce sources in deterministic order`() {
        val refs = mapOf(
            bravo.idString to RemoteBlobRef("blob-b"),
            alpha.idString to RemoteBlobRef("blob-a"),
        )
        val result = orderBlobDownloadSources(
            connectorRefs = refs,
            activeConnectorIds = listOf(alpha, bravo),
            runningConnectorIds = emptyList(),
        )
        // Both are paused → all in the "paused" bucket → sort by idString. alpha < bravo.
        result.keys.toList() shouldBe listOf(alpha, bravo)
    }

    @Test
    fun `no active connectors returns empty regardless of connectorRefs`() {
        val result = orderBlobDownloadSources(
            connectorRefs = mapOf(
                alpha.idString to RemoteBlobRef("blob-a"),
                bravo.idString to RemoteBlobRef("blob-b"),
            ),
            activeConnectorIds = emptyList(),
            runningConnectorIds = emptyList(),
        )
        result shouldBe emptyMap()
    }
}

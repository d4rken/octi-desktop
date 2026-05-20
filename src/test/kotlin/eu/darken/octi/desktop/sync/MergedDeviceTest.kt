package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class MergedDeviceTest {

    private fun cId(account: String): ConnectorId = ConnectorId(
        type = ConnectorType.OCTISERVER,
        subtype = "host.example.com",
        account = account,
    )

    private fun device(id: String, lastSeen: Instant? = null, capabilities: Set<String>? = null) =
        DevicesResponse.Device(id = id, lastSeen = lastSeen, capabilities = capabilities)

    @Test
    fun `empty map produces empty list`() {
        mergeDeviceLists(emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `single connector single device passes through with that source`() {
        val c = cId("a")
        val d = device("dev-1", Instant.fromEpochMilliseconds(100))
        val merged = mergeDeviceLists(mapOf(c to listOf(d)))
        merged.size shouldBe 1
        merged[0].device shouldBe d
        merged[0].sources shouldBe setOf(c)
    }

    @Test
    fun `same device on two connectors picks newest lastSeen and unions sources`() {
        val cA = cId("a")
        val cB = cId("b")
        val older = device("dev-1", Instant.fromEpochMilliseconds(100), capabilities = setOf("encryption:_reported"))
        val newer = device("dev-1", Instant.fromEpochMilliseconds(200), capabilities = setOf("encryption:aes256_gcm_siv"))
        val merged = mergeDeviceLists(mapOf(cA to listOf(older), cB to listOf(newer)))
        merged.size shouldBe 1
        // Representative is the newer row (modulo the merged capabilities field).
        merged[0].device.id shouldBe "dev-1"
        merged[0].device.lastSeen shouldBe Instant.fromEpochMilliseconds(200)
        merged[0].sources shouldBe setOf(cA, cB)
        // Capabilities union — both sources contributed.
        merged[0].device.capabilities shouldBe setOf("encryption:_reported", "encryption:aes256_gcm_siv")
    }

    @Test
    fun `null capabilities stay null when NO source reported a non-null set`() {
        val cA = cId("a")
        val cB = cId("b")
        val merged = mergeDeviceLists(
            mapOf(
                cA to listOf(device("dev-1", capabilities = null)),
                cB to listOf(device("dev-1", capabilities = null)),
            ),
        )
        // The null-vs-empty distinction is load-bearing in the capability authority rules.
        // Merging two nulls must NOT collapse to empty-set.
        merged[0].device.capabilities shouldBe null
    }

    @Test
    fun `empty-set capability from one source survives even when other source reports null`() {
        val cA = cId("a")
        val cB = cId("b")
        val merged = mergeDeviceLists(
            mapOf(
                cA to listOf(device("dev-1", capabilities = null)),
                cB to listOf(device("dev-1", capabilities = emptySet())),
            ),
        )
        // At least one source reported a non-null set → result is non-null (here, empty).
        merged[0].device.capabilities shouldBe emptySet()
    }

    @Test
    fun `distinct devices remain distinct entries`() {
        val c = cId("a")
        val merged = mergeDeviceLists(
            mapOf(c to listOf(device("dev-1"), device("dev-2"))),
        )
        merged.map { it.device.id }.toSet() shouldBe setOf("dev-1", "dev-2")
    }

    @Test
    fun `all-null lastSeen falls back to first row encountered`() {
        val cA = cId("a")
        val cB = cId("b")
        val a = device("dev-1", lastSeen = null, capabilities = setOf("a"))
        val b = device("dev-1", lastSeen = null, capabilities = setOf("b"))
        val merged = mergeDeviceLists(mapOf(cA to listOf(a), cB to listOf(b)))
        merged.size shouldBe 1
        // Capabilities still union regardless of who was representative.
        merged[0].device.capabilities shouldBe setOf("a", "b")
    }
}

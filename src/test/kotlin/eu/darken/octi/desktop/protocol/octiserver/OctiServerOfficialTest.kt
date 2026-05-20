package eu.darken.octi.desktop.protocol.octiserver

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards against testing-server addresses leaking back into the production enum (the AppImage
 * is unsigned so any string in the binary is grep-able by users). Pair test: catches both an
 * accidentally-readded LOCAL entry AND a typo in PROD/BETA domains.
 */
class OctiServerOfficialTest {

    @Test
    @DisplayName("Official has exactly PROD and BETA — no LOCAL or other testing addresses")
    fun officialEnumExpectedShape() {
        OctiServer.Official.values().map { it.name } shouldContainExactlyInAnyOrder
            listOf("PROD", "BETA")
    }

    @Test
    @DisplayName("PROD points at the canonical kserver domain")
    fun prodAddress() {
        OctiServer.Official.PROD.address.domain shouldBe "prod.kserver.octi.darken.eu"
        OctiServer.Official.PROD.address.protocol shouldBe "https"
        OctiServer.Official.PROD.address.port shouldBe 443
    }

    @Test
    @DisplayName("No Official entry references the internal blasphemy.greenkingdom test host")
    fun noTestingHost() {
        OctiServer.Official.values().forEach { official ->
            official.address.domain.shouldNotContain("blasphemy")
            official.address.domain.shouldNotContain("greenkingdom")
            official.address.address.shouldNotContain("blasphemy.greenkingdom")
        }
    }
}

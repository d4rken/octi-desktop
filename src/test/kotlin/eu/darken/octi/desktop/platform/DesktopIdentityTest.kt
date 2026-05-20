package eu.darken.octi.desktop.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Channel-aware identity is exercised by walking the [DesktopIdentity.fromChannel] mapping
 * directly. This sidesteps the fact that `BuildConfig.CHANNEL` is build-time pinned (the CI
 * test job builds with the default `stable` channel) and lets us assert canary's strings in
 * the same JVM as stable's.
 */
class DesktopIdentityTest {

    @Test
    @DisplayName("stable identity has the unsuffixed strings")
    fun stableValues() {
        val id = DesktopIdentity.fromChannel("stable")
        id shouldBe DesktopIdentity.Stable
        id.appName shouldBe "octi"
        id.credentialsKeyPrefix shouldBe "octiserver.credentials"
        id.keystoreServiceLabel shouldBe "eu.darken.octi.desktop"
        id.credentialsKeyFor("kserver-host-abc") shouldBe "octiserver.credentials.kserver-host-abc"
    }

    @Test
    @DisplayName("canary identity has the .canary-suffixed strings")
    fun canaryValues() {
        val id = DesktopIdentity.fromChannel("canary")
        id shouldBe DesktopIdentity.Canary
        id.appName shouldBe "octi-canary"
        id.credentialsKeyPrefix shouldBe "octiserver.credentials.canary"
        id.keystoreServiceLabel shouldBe "eu.darken.octi.desktop.canary"
        id.credentialsKeyFor("kserver-host-abc") shouldBe
            "octiserver.credentials.canary.kserver-host-abc"
    }

    @Test
    @DisplayName("no field overlaps between channels — guards against accidental cross-channel storage access")
    fun noFieldOverlaps() {
        val stable = DesktopIdentity.Stable
        val canary = DesktopIdentity.Canary
        stable.appName shouldNotBe canary.appName
        stable.credentialsKeyPrefix shouldNotBe canary.credentialsKeyPrefix
        stable.keystoreServiceLabel shouldNotBe canary.keystoreServiceLabel
        // A given connector idString must produce different keystore keys on each channel —
        // this is the durable property that prevents canary from reading stable's credentials.
        stable.credentialsKeyFor("kserver-host-abc") shouldNotBe
            canary.credentialsKeyFor("kserver-host-abc")
    }

    @Test
    @DisplayName("unknown channel throws — refuses to silently fall back to stable")
    fun unknownChannelThrows() {
        val e = shouldThrow<IllegalStateException> { DesktopIdentity.fromChannel("bogus") }
        e.message!!.shouldContain("bogus")
    }

    @Test
    @DisplayName("empty channel throws")
    fun emptyChannelThrows() {
        shouldThrow<IllegalStateException> { DesktopIdentity.fromChannel("") }
    }
}

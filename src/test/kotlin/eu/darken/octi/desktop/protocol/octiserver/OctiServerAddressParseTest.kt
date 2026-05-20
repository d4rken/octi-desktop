package eu.darken.octi.desktop.protocol.octiserver

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OctiServerAddressParseTest {

    private fun parse(raw: String) = OctiServer.Address.tryParse(raw)

    @Test
    @DisplayName("bare host defaults to https:443")
    fun bareHost() {
        parse("octi.example.com").getOrThrow() shouldBe
            OctiServer.Address(domain = "octi.example.com", protocol = "https", port = 443)
    }

    @Test
    @DisplayName("host:port defaults to https with the given port")
    fun hostPort() {
        parse("octi.example.com:8443").getOrThrow() shouldBe
            OctiServer.Address(domain = "octi.example.com", protocol = "https", port = 8443)
    }

    @Test
    @DisplayName("http://host fills default port 80")
    fun httpHost() {
        parse("http://octi.example.com").getOrThrow() shouldBe
            OctiServer.Address(domain = "octi.example.com", protocol = "http", port = 80)
    }

    @Test
    @DisplayName("https://host:port preserves the explicit port")
    fun httpsHostPort() {
        parse("https://octi.example.com:9443").getOrThrow() shouldBe
            OctiServer.Address(domain = "octi.example.com", protocol = "https", port = 9443)
    }

    @Test
    @DisplayName("scheme is lowercased before being stored")
    fun mixedCaseScheme() {
        parse("HTTPS://octi.example.com").getOrThrow().protocol shouldBe "https"
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed")
    fun whitespaceTrimmed() {
        parse("   octi.example.com   ").getOrThrow().domain shouldBe "octi.example.com"
    }

    @Test
    @DisplayName("IPv4 dotted-quad is accepted")
    fun ipv4() {
        parse("192.168.1.10:8443").getOrThrow() shouldBe
            OctiServer.Address(domain = "192.168.1.10", protocol = "https", port = 8443)
    }

    @Test
    @DisplayName("hyphenated DNS hostnames pass the host regex")
    fun hyphenatedDns() {
        parse("kserver-eu-1.example.com").isSuccess shouldBe true
    }

    // --- Rejections ---

    @Test
    @DisplayName("empty input rejected")
    fun empty() {
        val e = parse("").exceptionOrNull()!!
        e.shouldBeInstanceOf<OctiServer.InvalidServerAddress>()
        e.message!!.shouldContain("empty")
    }

    @Test
    @DisplayName("whitespace-only input rejected")
    fun whitespaceOnly() {
        parse("   ").exceptionOrNull()!!.shouldBeInstanceOf<OctiServer.InvalidServerAddress>()
    }

    @Test
    @DisplayName("bare port without host rejected")
    fun barePort() {
        // ":8080" splits to host="" port="8080" → host-empty
        parse(":8080").exceptionOrNull()!!.message!!.shouldContain("Host")
    }

    @Test
    @DisplayName("non-numeric port rejected")
    fun nonNumericPort() {
        parse("octi.example.com:abc").exceptionOrNull()!!.message!!.shouldContain("Port")
    }

    @Test
    @DisplayName("trailing colon with no port rejected (host: → no silent default-port fallback)")
    fun trailingColonNoPort() {
        parse("octi.example.com:").exceptionOrNull()!!.message!!.shouldContain("Port")
        parse("https://octi.example.com:").exceptionOrNull()!!.message!!.shouldContain("Port")
    }

    @Test
    @DisplayName("port 0 rejected")
    fun portZero() {
        parse("octi.example.com:0").exceptionOrNull()!!.message!!.shouldContain("Port")
    }

    @Test
    @DisplayName("port 65536 rejected")
    fun portOutOfRange() {
        parse("octi.example.com:65536").exceptionOrNull()!!.message!!.shouldContain("Port")
    }

    @Test
    @DisplayName("non-http(s) scheme rejected")
    fun badScheme() {
        parse("ftp://octi.example.com").exceptionOrNull()!!.message!!.shouldContain("Scheme")
    }

    @Test
    @DisplayName("userinfo in URL rejected")
    fun userinfoRejected() {
        parse("http://user:pass@octi.example.com").exceptionOrNull()!!.message!!.shouldContain("Userinfo")
    }

    @Test
    @DisplayName("path component rejected")
    fun pathRejected() {
        parse("http://octi.example.com/v1").exceptionOrNull()!!.message!!.shouldContain("Path")
    }

    @Test
    @DisplayName("query component rejected")
    fun queryRejected() {
        parse("https://octi.example.com?x=1").exceptionOrNull()!!.message!!.shouldContain("Path")
    }

    @Test
    @DisplayName("fragment component rejected")
    fun fragmentRejected() {
        parse("https://octi.example.com#frag").exceptionOrNull()!!.message!!.shouldContain("Path")
    }

    @Test
    @DisplayName("IPv6 literal rejected (IPv4/DNS only)")
    fun ipv6Rejected() {
        parse("[::1]:8080").exceptionOrNull()!!.message!!.shouldContain("IPv6")
        parse("http://[2001:db8::1]:8443").exceptionOrNull()!!.message!!.shouldContain("IPv6")
    }

    @Test
    @DisplayName("invalid host characters rejected")
    fun invalidHostChars() {
        parse("octi_example.com").exceptionOrNull()!!.message!!.shouldContain("Host")
        parse("octi example.com").exceptionOrNull()!!.message!!.shouldContain("Host")
    }
}

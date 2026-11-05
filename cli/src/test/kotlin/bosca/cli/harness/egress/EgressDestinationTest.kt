package bosca.cli.harness.egress

import kotlin.test.*

class EgressDestinationTest {
    @Test fun `destinations normalize host case trailing dot and default port`() {
        assertEquals(EgressDestination("api.openai.com", 443), EgressDestination.parse("API.OpenAI.com."))
        assertEquals("mcp.example.com:8443", EgressDestination.parse("mcp.example.com:8443").toString())
        assertEquals(EgressDestination("10.0.0.1", 80), EgressDestination.parse("10.0.0.1:80"))
        assertEquals(EgressDestination("localhost", 65535), EgressDestination.parse("localhost:65535"))
    }

    @Test fun `wildcards IPv6 literals and malformed values are rejected`() {
        for (value in listOf("", "*.example.com", "*", "[::1]:443", "::1", "2001:db8::1", "host:0", "host:65536", "host:", "host:abc",
            "host:443:1", "user@host", "host/path", "host?x", "-bad.com", "bad-.com", "a..b", ".", "under_score.com", "a".repeat(64) + ".com",
            "host :443", "host:+443")) {
            assertFailsWith<IllegalArgumentException>(value) { EgressDestination.parse(value) }
            assertNull(EgressDestination.parseOrNull(value), value)
        }
    }

    @Test fun `an explicit port can be required`() {
        assertFailsWith<IllegalArgumentException> { EgressDestination.parse("kit-egress", requirePort = true) }
        assertEquals(EgressDestination("kit-egress", 3128), EgressDestination.parse("kit-egress:3128", requirePort = true))
    }

    @Test fun `proxy origins are plain http host and port`() {
        assertEquals(EgressDestination("kit-egress.bosca.svc", 3128), parseEgressProxyOrigin("http://kit-egress.bosca.svc:3128"))
        for (value in listOf("https://kit-egress:3128", "http://kit-egress", "http://kit-egress:3128/", "http://kit-egress:3128/path",
            "http://user:pass@kit-egress:3128", "http://kit-egress:3128?x=1", "http://kit-egress:3128#f", "kit-egress:3128", "http://[::1]:3128")) {
            assertFailsWith<IllegalArgumentException>(value) { parseEgressProxyOrigin(value) }
        }
    }
}

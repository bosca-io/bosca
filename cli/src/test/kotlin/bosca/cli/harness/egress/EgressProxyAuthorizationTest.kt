package bosca.cli.harness.egress

import java.util.Base64
import kotlin.test.*

class EgressProxyAuthorizationTest {
    private val session = "0b5f8f2e-5c0a-4d55-9a49-1b6f6d1c2e3f"
    private fun basic(text: String) = "Basic " + Base64.getEncoder().encodeToString(text.toByteArray())

    @Test fun `basic credentials carry the session and egress token`() {
        val credentials = EgressCredentials(session, "egress_token-value")
        assertEquals(credentials, parseProxyAuthorization(listOf(credentials.proxyAuthorization())))
        assertEquals(credentials, parseProxyAuthorization(listOf("basic " + credentials.proxyAuthorization().removePrefix("Basic "))))
        assertEquals(EgressCredentials(session, "a:b"), parseProxyAuthorization(listOf(basic("$session:a:b"))))
        assertFalse(credentials.toString().contains("egress_token-value"))
        assertFalse(credentials.cacheKey().contains("egress_token-value"))
        assertNotEquals(credentials.cacheKey(), credentials.copy(token = "other").cacheKey())
    }

    @Test fun `missing duplicate or malformed credentials are rejected`() {
        val valid = basic("$session:token")
        for (values in listOf(emptyList(), listOf(valid, valid), listOf("Bearer token"), listOf("Basic"), listOf("Basic !!!"),
            listOf(basic("token-only")), listOf(basic(":token")), listOf(basic("$session:")), listOf(basic("not-a-uuid:token")),
            listOf(basic(session.uppercase() + ":token")), listOf(basic("$session:to ken")), listOf(basic("$session:" + "t".repeat(513))))) {
            assertNull(parseProxyAuthorization(values), values.toString())
        }
    }
}

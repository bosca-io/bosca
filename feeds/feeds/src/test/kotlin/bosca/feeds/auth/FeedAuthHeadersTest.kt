package bosca.feeds.auth

import bosca.feeds.model.FeedAuth
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**the non-secret descriptor + secret produce the right outbound header (no secret → no header). */
class FeedAuthHeadersTest {

    @Test
    fun `none yields no headers`() {
        assertTrue(FeedAuthHeaders.forAuth(FeedAuth.None, "ignored").isEmpty())
    }

    @Test
    fun `bearer sets the Authorization header from the secret`() {
        assertEquals(mapOf("Authorization" to "Bearer tok"), FeedAuthHeaders.forAuth(FeedAuth.Bearer, "tok"))
    }

    @Test
    fun `basic base64-encodes username and secret`() {
        val headers = FeedAuthHeaders.forAuth(FeedAuth.Basic("user"), "pass")
        val expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".toByteArray(Charsets.UTF_8))
        assertEquals(expected, headers["Authorization"])
    }

    @Test
    fun `api key sets the configured header to the secret`() {
        assertEquals(mapOf("X-API-Key" to "secret"), FeedAuthHeaders.forAuth(FeedAuth.ApiKey("X-API-Key"), "secret"))
    }

    @Test
    fun `a kind needing a secret yields no header when none is set`() {
        assertTrue(FeedAuthHeaders.forAuth(FeedAuth.Bearer, null).isEmpty())
    }

    @Test
    fun `oauth2 is not implemented yet`() {
        assertFailsWith<UnsupportedOperationException> {
            FeedAuthHeaders.forAuth(FeedAuth.OAuth2(tokenUrl = "https://t", clientId = "cid"), "sec")
        }
    }
}

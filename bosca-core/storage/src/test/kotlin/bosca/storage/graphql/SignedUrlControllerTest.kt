package bosca.storage.graphql

import bosca.storage.service.SignedUrl
import bosca.storage.service.SignedUrlHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignedUrlControllerTest {

    private val controller = SignedUrlController()

    @Test
    fun `url returns the URL from the signed url`() {
        val signedUrl = SignedUrl(
            url = "https://example.com/file?expires=123&signature=abc",
            headers = emptyList()
        )
        assertEquals("https://example.com/file?expires=123&signature=abc", controller.url(signedUrl))
    }

    @Test
    fun `headers returns the headers list from the signed url`() {
        val headers = listOf(
            SignedUrlHeader(name = "Authorization", value = "Bearer token123"),
            SignedUrlHeader(name = "X-Custom", value = "custom-value")
        )
        val signedUrl = SignedUrl(url = "https://example.com/file", headers = headers)
        val result = controller.headers(signedUrl)
        assertEquals(2, result.size)
        assertEquals("Authorization", result[0].name)
        assertEquals("Bearer token123", result[0].value)
    }

    @Test
    fun `headers returns empty list when no headers present`() {
        val signedUrl = SignedUrl(url = "https://example.com/file", headers = emptyList())
        assertTrue(controller.headers(signedUrl).isEmpty())
    }
}

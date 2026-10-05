package bosca.storage.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignedUrlTest {

    @Test
    fun `SignedUrlHeader stores name and value`() {
        val header = SignedUrlHeader(name = "Authorization", value = "Bearer token123")
        assertEquals("Authorization", header.name)
        assertEquals("Bearer token123", header.value)
    }

    @Test
    fun `SignedUrl stores url and headers`() {
        val headers = listOf(
            SignedUrlHeader("Authorization", "Bearer token"),
            SignedUrlHeader("Content-Type", "application/octet-stream")
        )
        val signedUrl = SignedUrl(url = "https://storage.example.com/file.pdf", headers = headers)
        assertEquals("https://storage.example.com/file.pdf", signedUrl.url)
        assertEquals(2, signedUrl.headers.size)
    }

    @Test
    fun `SignedUrl with empty headers`() {
        val signedUrl = SignedUrl(url = "https://example.com/file", headers = emptyList())
        assertTrue(signedUrl.headers.isEmpty())
    }

    @Test
    fun `SignedUrlHeader data class equality`() {
        val h1 = SignedUrlHeader("name", "value")
        val h2 = SignedUrlHeader("name", "value")
        assertEquals(h1, h2)
    }

    @Test
    fun `SignedUrl data class equality`() {
        val headers = listOf(SignedUrlHeader("key", "val"))
        val s1 = SignedUrl(url = "https://example.com", headers = headers)
        val s2 = SignedUrl(url = "https://example.com", headers = headers)
        assertEquals(s1, s2)
    }

    @Test
    fun `SignedUrl data class copy`() {
        val signedUrl = SignedUrl(url = "https://old.com", headers = emptyList())
        val modified = signedUrl.copy(url = "https://new.com")
        assertEquals("https://new.com", modified.url)
    }
}

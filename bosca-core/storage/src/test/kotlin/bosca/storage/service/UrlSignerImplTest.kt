package bosca.storage.service

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlSignerImplTest {

    private val secretKey = "test-secret-key-for-signing"
    private val signer = UrlSignerImpl(secretKey)

    @Test
    fun `sign adds expires and signature parameters to URL`() {
        val signed = signer.sign("https://example.com/file.pdf", 3600)
        val uri = URI.create(signed)
        val params = uri.query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        assertTrue(params.containsKey("expires"), "Signed URL should contain expires parameter")
        assertTrue(params.containsKey("signature"), "Signed URL should contain signature parameter")
        assertTrue(params["signature"]!!.isNotEmpty(), "Signature should not be empty")
    }

    @Test
    fun `sign with existing query parameters appends correctly`() {
        val signed = signer.sign("https://example.com/file.pdf?token=abc", 3600)
        val uri = URI.create(signed)
        val params = uri.query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        assertEquals("abc", params["token"], "Original query parameter should be preserved")
        assertTrue(params.containsKey("expires"), "Signed URL should contain expires parameter")
        assertTrue(params.containsKey("signature"), "Signed URL should contain signature parameter")
    }

    @Test
    fun `sign and verify round-trip returns true`() {
        val signed = signer.sign("https://example.com/resource/123", 3600)
        val result = signer.verify(URI.create(signed))
        assertTrue(result, "Verifying a freshly signed URL should return true")
    }

    @Test
    fun `sign and verify round-trip with query parameters returns true`() {
        val signed = signer.sign("https://example.com/resource?type=image&size=large", 3600)
        val result = signer.verify(URI.create(signed))
        assertTrue(result, "Verifying a freshly signed URL with existing query params should return true")
    }

    @Test
    fun `verify with tampered signature returns false`() {
        val signed = signer.sign("https://example.com/resource/123", 3600)
        val tampered = signed.replaceRange(signed.length - 4, signed.length, "0000")
        val result = signer.verify(URI.create(tampered))
        assertFalse(result, "Verifying a URL with a tampered signature should return false")
    }

    @Test
    fun `verify with expired URL returns false`() {
        // Sign with a negative duration to force expiration in the past
        val signed = signer.sign("https://example.com/resource/123", -3600)
        val result = signer.verify(URI.create(signed))
        assertFalse(result, "Verifying an expired URL should return false")
    }

    @Test
    fun `verify with missing signature returns false`() {
        // Construct a URL with expires but no signature
        val url = "https://example.com/resource/123?expires=9999999999"
        val result = signer.verify(URI.create(url))
        assertFalse(result, "Verifying a URL without a signature should return false")
    }

    @Test
    fun `verify with different secret key returns false`() {
        val otherSigner = UrlSignerImpl("different-secret-key")
        val signed = signer.sign("https://example.com/resource/123", 3600)
        val result = otherSigner.verify(URI.create(signed))
        assertFalse(result, "Verifying with a different secret key should return false")
    }
}

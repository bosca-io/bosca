package bosca.security.service

import kotlin.test.Test
import kotlin.test.assertEquals

class AppOriginsTest {

    private val allowed = listOf("https://app.example.com", "http://localhost:3000")
    private val default = "https://admin.example.com"

    @Test
    fun `prefers an explicit allow-listed origin over the request origin`() {
        assertEquals("https://app.example.com", AppOrigins.resolve("https://app.example.com", "http://localhost:3000", allowed, default))
    }

    @Test
    fun `derives from the request origin when no explicit value is given`() {
        assertEquals("http://localhost:3000", AppOrigins.resolve(null, "http://localhost:3000", allowed, default))
    }

    @Test
    fun `falls back to the default when the candidate is not allow-listed`() {
        // An attacker-supplied host must never be used to build an email link (phishing open-redirect).
        assertEquals(default, AppOrigins.resolve("https://evil.example", null, allowed, default))
        assertEquals(default, AppOrigins.resolve(null, "https://evil.example", allowed, default))
    }

    @Test
    fun `falls back to the default when nothing is supplied`() {
        assertEquals(default, AppOrigins.resolve(null, null, allowed, default))
    }

    @Test
    fun `does not fall through to the request origin when an explicit value fails validation`() {
        // Explicit-but-disallowed resolves to the default, not the (allow-listed) request origin.
        assertEquals(default, AppOrigins.resolve("https://evil.example", "https://app.example.com", allowed, default))
    }

    @Test
    fun `normalizes the candidate to its origin, dropping path and query`() {
        assertEquals("https://app.example.com", AppOrigins.resolve("https://app.example.com/auth/verify?token=x", null, allowed, default))
    }

    @Test
    fun `matches allow-list entries by origin including non-default ports`() {
        assertEquals("http://localhost:3000", AppOrigins.resolve("http://localhost:3000/", null, allowed, default))
    }

    @Test
    fun `treats a blank explicit value as absent and derives from the request`() {
        assertEquals("https://app.example.com", AppOrigins.resolve("", "https://app.example.com", allowed, default))
    }

    @Test
    fun `rejects malformed and relative candidates and defaults a protocol-relative origin to https`() {
        assertEquals(default, AppOrigins.resolve("::::", null, allowed, default))
        assertEquals(default, AppOrigins.resolve("relative/path", null, allowed, default))
        assertEquals("https://app.example.com", AppOrigins.resolve("//app.example.com", null, allowed, default))
        assertEquals(
            "https://app.example.com",
            AppOrigins.resolve(
                "https://app.example.com",
                null,
                listOf("::::", "https://app.example.com"),
                default,
            ),
        )
    }
}

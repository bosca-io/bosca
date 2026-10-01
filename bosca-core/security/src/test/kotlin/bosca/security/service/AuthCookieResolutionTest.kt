package bosca.security.service

import bosca.server.ServerCall
import bosca.server.RequestOrigin
import bosca.server.ServerRequest
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthCookieResolutionTest {

    private val preview = AuthCookiePrefix("_bat_preview", listOf("preview.example.com"))
    private val configuration = mockk<SecurityConfiguration> {
        every { authCookiePrefixes } returns listOf(preview)
    }

    @Test
    fun `resolves an exact configured domain`() {
        val call = call("preview.example.com", "https://preview.example.com")

        val resolved = configuration.resolveAuthCookiePrefix(call)

        assertEquals(preview.prefix, resolved)
    }

    @Test
    fun `configured addressed domain wins over a different application origin`() {
        val resolved = configuration.resolveAuthCookiePrefix(
            call("preview.example.com", "https://studio.example.com")
        )

        assertEquals(preview.prefix, resolved)
    }

    @Test
    fun `configured application origin is used behind an unconfigured proxy host`() {
        val resolved = configuration.resolveAuthCookiePrefix(
            call("api.internal.example.com", "https://preview.example.com")
        )

        assertEquals(preview.prefix, resolved)
    }

    @Test
    fun `unconfigured addressed and application domains use the default cookie`() {
        assertNull(configuration.resolveAuthCookiePrefix(call("api.example.com", "https://studio.example.com")))
    }

    @Test
    fun `extracts normalized hosts from origins`() {
        assertEquals("preview.example.com", authCookieDomain("https://PREVIEW.EXAMPLE.COM/path"))
        assertNull(authCookieDomain("not an origin"))
    }

    @Test
    fun `unconfigured proxy hosts cannot obtain a custom prefix from a missing or invalid public host`() {
        for (origin in listOf("/relative", "not an origin")) {
            assertNull(configuration.resolveAuthCookiePrefix(call("api.internal", origin)))
        }
        assertNull(authCookieDomain("/relative"))
    }

    @Test
    fun `an empty prefix configuration preserves default browser authentication`() {
        val empty = mockk<SecurityConfiguration> { every { authCookiePrefixes } returns emptyList() }
        assertNull(empty.resolveAuthCookiePrefix(call("preview.example.com", "https://preview.example.com")))
    }

    private fun call(addressedHost: String, appOrigin: String): ServerCall {
        val request = mockk<ServerRequest> {
            every { origin } returns RequestOrigin("https", addressedHost, 443)
            every { this@mockk.appOrigin } returns appOrigin
        }
        return mockk { every { this@mockk.request } returns request }
    }
}

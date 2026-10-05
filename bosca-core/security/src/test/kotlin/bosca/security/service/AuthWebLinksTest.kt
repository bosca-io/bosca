package bosca.security.service

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthWebLinksTest {

    @Test
    fun `builds the Studio auth routes from the app origin`() {
        val base = "https://admin.example.com"
        assertEquals("https://admin.example.com/auth/verify?token=abc", AuthWebLinks.verify(base, "abc"))
        assertEquals("https://admin.example.com/auth/reset-password?token=abc", AuthWebLinks.resetPassword(base, "abc"))
        assertEquals("https://admin.example.com/auth/link/confirm?proof=abc", AuthWebLinks.accountLink(base, "abc"))
    }

    @Test
    fun `trims a trailing slash on the app origin so the path separator never doubles`() {
        assertEquals("https://app.test/auth/verify?token=t", AuthWebLinks.verify("https://app.test/", "t"))
    }

    @Test
    fun `url-encodes the token so reserved characters survive the query string`() {
        // A token with '+' and '/' (Base64-ish) must not be interpreted as a space or path separator.
        val link = AuthWebLinks.verify("https://app.test", "a+b/c=d")
        assertEquals("https://app.test/auth/verify?token=a%2Bb%2Fc%3Dd", link)
    }
}

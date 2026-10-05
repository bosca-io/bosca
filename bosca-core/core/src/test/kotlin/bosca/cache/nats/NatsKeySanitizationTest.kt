package bosca.cache.nats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NatsKeySanitizationTest {

    @Test
    fun `escapes key separators without changing safe characters`() {
        assertEquals(
            "str_003a_003aauth_003arate-limit_003a_003asignup_003auser",
            "str::auth:rate-limit::signup:user".sanitizeForNats(),
        )
    }

    @Test
    fun `escapes at sign and plus in email addresses`() {
        val sanitized = "str::auth:rate-limit::signup:user+tag@example.com".sanitizeForNats()
        assertTrue(sanitized.matches(Regex("[A-Za-z0-9_/=-]+")))
        assertEquals("str_003a_003aauth_003arate-limit_003a_003asignup_003auser_002btag_0040example_002ecom", sanitized)
    }

    @Test
    fun `preserves safe characters and escapes dots and underscores`() {
        assertEquals("ab/c-d=e", "ab/c-d=e".sanitizeForNats())
        assertEquals("a_002eb/c-d_005fe=f", "a.b/c-d_e=f".sanitizeForNats())
    }

    @Test
    fun `escapes every dot without creating empty NATS subject tokens`() {
        assertEquals("str_003a_003aslugs_003a_003awhen-i-feel_002e_002e_002e", "str::slugs::when-i-feel...".sanitizeForNats())
        assertEquals("_002eleading", ".leading".sanitizeForNats())
        assertEquals("middle_002e_002egap", "middle..gap".sanitizeForNats())
        assertEquals("trailing_002e", "trailing.".sanitizeForNats())
    }

    @Test
    fun `distinct slug and separator forms retain distinct keys`() {
        val keys = listOf("when-i-feel...", "when-i-feel_", "middle..gap", "middle_gap", "a::b", "a--b")
            .map { "str::slugs::$it".sanitizeForNats() }
        assertEquals(keys.size, keys.toSet().size)

        assertEquals("a_003a_003ab_003ac", "a::b:c".sanitizeForNats())
    }

    @Test
    fun `encoded composite prefix matches the encoded full key`() {
        val prefix = "gid::groups::staff.".sanitizeForNats()
        val full = "gid::groups::staff.::ADMIN".sanitizeForNats()
        assertTrue(full.startsWith(prefix))
    }

    @Test
    fun `escapes spaces distinctly from underscores`() {
        assertEquals("hello_0020world", "hello world".sanitizeForNats())
        assertEquals("hello_005fworld", "hello_world".sanitizeForNats())
    }

    @Test
    fun `handles empty string`() {
        assertEquals("", "".sanitizeForNats())
    }

    @Test
    fun `handles string with only unsafe characters`() {
        val sanitized = "!@#\$%^&*()".sanitizeForNats()
        assertTrue(sanitized.matches(Regex("[A-Za-z0-9_/=-]*")))
    }
}

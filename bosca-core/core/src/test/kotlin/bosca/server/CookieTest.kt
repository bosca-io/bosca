package bosca.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieTest {

    @Test
    fun `toSetCookieString with name and value only`() {
        val cookie = Cookie(name = "session", value = "abc123")
        assertEquals("session=abc123", cookie.toSetCookieString())
    }

    @Test
    fun `toSetCookieString with domain`() {
        val cookie = Cookie(name = "session", value = "abc", domain = "example.com")
        val result = cookie.toSetCookieString()
        assertTrue(result.contains("Domain=example.com"))
    }

    @Test
    fun `toSetCookieString with path`() {
        val cookie = Cookie(name = "session", value = "abc", path = "/api")
        val result = cookie.toSetCookieString()
        assertTrue(result.contains("Path=/api"))
    }

    @Test
    fun `toSetCookieString with maxAge`() {
        val cookie = Cookie(name = "session", value = "abc", maxAge = 3600)
        val result = cookie.toSetCookieString()
        assertTrue(result.contains("Max-Age=3600"))
    }

    @Test
    fun `toSetCookieString with secure flag`() {
        val cookie = Cookie(name = "session", value = "abc", secure = true)
        val result = cookie.toSetCookieString()
        assertTrue(result.contains("; Secure"))
    }

    @Test
    fun `toSetCookieString without secure flag`() {
        val cookie = Cookie(name = "session", value = "abc", secure = false)
        val result = cookie.toSetCookieString()
        assertTrue(!result.contains("Secure"))
    }

    @Test
    fun `toSetCookieString with httpOnly flag`() {
        val cookie = Cookie(name = "session", value = "abc", httpOnly = true)
        val result = cookie.toSetCookieString()
        assertTrue(result.contains("; HttpOnly"))
    }

    @Test
    fun `toSetCookieString with all attributes`() {
        val cookie = Cookie(
            name = "session",
            value = "abc123",
            maxAge = 3600,
            domain = "example.com",
            path = "/",
            secure = true,
            httpOnly = true
        )
        val result = cookie.toSetCookieString()
        assertTrue(result.startsWith("session=abc123"))
        assertTrue(result.contains("Domain=example.com"))
        assertTrue(result.contains("Path=/"))
        assertTrue(result.contains("Max-Age=3600"))
        assertTrue(result.contains("Secure"))
        assertTrue(result.contains("HttpOnly"))
    }

    @Test
    fun `toSetCookieString with extensions`() {
        val cookie = Cookie(
            name = "test",
            value = "val",
            extensions = mapOf("SameSite" to "Lax")
        )
        val result = cookie.toSetCookieString()
        assertTrue(result.contains("; SameSite=Lax"))
    }

    @Test
    fun `toSetCookieString covers expires sameSite sanitization and domain cleanup`() {
        val result = Cookie(
            name = "session",
            value = "value",
            expires = 0,
            domain = "..sub-domain exa mple!.com",
            path = "/safe;\r\n\tpath",
            sameSite = "Strict;\n",
            extensions = mapOf("Bad;\nKey" to "value;\r\n"),
        ).toSetCookieString()

        assertTrue(result.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        assertTrue(result.contains("Domain=sub-domainexample.com"))
        assertTrue(result.contains("Path=/safepath"))
        assertTrue(result.contains("SameSite=Strict"))
        assertTrue(result.contains("BadKey=value"))
    }

    @Test
    fun `cookie values cover empty plain quoted escaped and unicode forms`() {
        assertEquals("empty=", Cookie("empty", "").toSetCookieString())
        assertEquals("plain=abc-123_~", Cookie("plain", "abc-123_~").toSetCookieString())
        assertEquals("space=\"hello world\"", Cookie("space", "hello world").toSetCookieString())
        assertEquals("escaped=\"a\\\\b\\\"c\"", Cookie("escaped", "a\\b\"c").toSetCookieString())
        assertEquals("unicode=\"café\"", Cookie("unicode", "café").toSetCookieString())
    }

    @Test
    fun `cookie names reject empty control non-ascii whitespace and delimiters`() {
        assertFailsWith<IllegalArgumentException> { Cookie("", "value").toSetCookieString() }
        listOf("bad\u0001", "badé", "bad name", "bad\tname", "bad;name", "bad=name", "bad,name").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { Cookie(name, "value").toSetCookieString() }
        }
    }

    @Test
    fun `cookie domain enforces total length`() {
        val allowed = "a".repeat(253)
        assertTrue(Cookie("name", "value", domain = allowed).toSetCookieString().contains(allowed))
        assertFailsWith<IllegalArgumentException> {
            Cookie("name", "value", domain = "a".repeat(254)).toSetCookieString()
        }
    }

    @Test
    fun `toSetCookieString with extension without value`() {
        val cookie = Cookie(
            name = "test",
            value = "val",
            extensions = mapOf("Partitioned" to null)
        )
        val result = cookie.toSetCookieString()
        assertTrue(result.contains("; Partitioned"))
    }

    @Test
    fun `toSetCookieString attribute order is domain path maxAge secure httpOnly`() {
        val cookie = Cookie(
            name = "s",
            value = "v",
            maxAge = 60,
            domain = "d.com",
            path = "/p",
            secure = true,
            httpOnly = true
        )
        val result = cookie.toSetCookieString()
        val domainIdx = result.indexOf("Domain=")
        val pathIdx = result.indexOf("Path=")
        val maxAgeIdx = result.indexOf("Max-Age=")
        val secureIdx = result.indexOf("Secure")
        val httpOnlyIdx = result.indexOf("HttpOnly")
        assertTrue(domainIdx < pathIdx)
        assertTrue(pathIdx < maxAgeIdx)
        assertTrue(maxAgeIdx < secureIdx)
        assertTrue(secureIdx < httpOnlyIdx)
    }

    // --- RequestCookies ---

    @Test
    fun `RequestCookies parses single cookie`() {
        val cookies = RequestCookies("session=abc123")
        assertEquals("abc123", cookies["session"])
    }

    @Test
    fun `RequestCookies parses multiple cookies`() {
        val cookies = RequestCookies("session=abc123; user=john; theme=dark")
        assertEquals("abc123", cookies["session"])
        assertEquals("john", cookies["user"])
        assertEquals("dark", cookies["theme"])
    }

    @Test
    fun `RequestCookies returns null for missing cookie`() {
        val cookies = RequestCookies("session=abc123")
        assertNull(cookies["missing"])
    }

    @Test
    fun `RequestCookies with null header returns empty`() {
        val cookies = RequestCookies(null)
        assertNull(cookies["anything"])
        assertTrue(cookies.all.isEmpty())
    }

    @Test
    fun `RequestCookies all returns all parsed cookies`() {
        val cookies = RequestCookies("a=1; b=2")
        val all = cookies.all
        assertEquals(2, all.size)
        assertEquals("1", all["a"])
        assertEquals("2", all["b"])
    }

    @Test
    fun `RequestCookies handles cookie with equals in value`() {
        val cookies = RequestCookies("token=abc=def")
        assertEquals("abc=def", cookies["token"])
    }

    @Test
    fun `RequestCookies parses repeated headers quotes and valueless parts`() {
        val cookies = RequestCookies(listOf("quoted=\"hello\"; flag", "second=value"))
        assertEquals("hello", cookies["quoted"])
        assertEquals("", cookies["flag"])
        assertEquals("value", cookies["second"])
    }

    // --- ResponseCookies ---

    @Test
    fun `ResponseCookies append with Cookie object`() {
        val rc = ResponseCookies()
        val cookie = Cookie("session", "abc")
        rc.append(cookie)
        assertEquals(1, rc.cookies.size)
        assertEquals("session", rc.cookies[0].name)
        assertEquals("abc", rc.cookies[0].value)
    }

    @Test
    fun `ResponseCookies append with parameters`() {
        val rc = ResponseCookies()
        rc.append(
            name = "session",
            value = "abc",
            maxAge = 3600,
            secure = true,
            httpOnly = true,
            path = "/",
            domain = "example.com"
        )
        assertEquals(1, rc.cookies.size)
        val cookie = rc.cookies[0]
        assertEquals("session", cookie.name)
        assertEquals("abc", cookie.value)
        assertEquals(3600, cookie.maxAge)
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
        assertEquals("/", cookie.path)
        assertEquals("example.com", cookie.domain)
    }

    @Test
    fun `ResponseCookies append multiple cookies`() {
        val rc = ResponseCookies()
        rc.append(Cookie("a", "1"))
        rc.append(Cookie("b", "2"))
        rc.append(Cookie("c", "3"))
        assertEquals(3, rc.cookies.size)
    }

    @Test
    fun `ResponseCookies starts empty`() {
        val rc = ResponseCookies()
        assertTrue(rc.cookies.isEmpty())
    }
}

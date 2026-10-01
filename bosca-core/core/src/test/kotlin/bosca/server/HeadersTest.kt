package bosca.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadersTest {

    @Test
    fun `Empty headers has no entries`() {
        val headers = Headers.Empty
        assertTrue(headers.isEmpty())
        assertTrue(headers.names().isEmpty())
    }

    @Test
    fun `Empty headers get returns null`() {
        assertNull(Headers.Empty["Content-Type"])
    }

    @Test
    fun `headersOf no args returns empty`() {
        val headers = headersOf()
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `headersOf single name-value pair`() {
        val headers = headersOf("Content-Type", "application/json")
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `headersOf vararg pairs`() {
        val headers = headersOf(
            "Content-Type" to listOf("application/json"),
            "Accept" to listOf("text/html")
        )
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("text/html", headers["Accept"])
    }

    @Test
    fun `headersOf with multiple values for same header`() {
        val headers = headersOf(
            "Set-Cookie" to listOf("a=1", "b=2")
        )
        val all = headers.getAll("Set-Cookie")
        assertNotNull(all)
        assertEquals(2, all.size)
        assertEquals("a=1", all[0])
        assertEquals("b=2", all[1])
    }

    @Test
    fun `case-insensitive header lookup`() {
        val headers = headersOf("Content-Type", "application/json")
        assertEquals("application/json", headers["content-type"])
        assertEquals("application/json", headers["CONTENT-TYPE"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `case-insensitive getAll lookup`() {
        val headers = headersOf("Accept" to listOf("text/html", "application/json"))
        val all = headers.getAll("accept")
        assertNotNull(all)
        assertEquals(2, all.size)
    }

    @Test
    fun `names returns original casing`() {
        val headers = headersOf("Content-Type" to listOf("application/json"))
        assertTrue(headers.names().contains("Content-Type"))
    }

    @Test
    fun `entries returns all header entries`() {
        val headers = headersOf(
            "Content-Type" to listOf("application/json"),
            "Accept" to listOf("text/html")
        )
        val entries = headers.entries()
        assertEquals(2, entries.size)
    }

    @Test
    fun `getAll returns empty list for missing header`() {
        val headers = headersOf("Content-Type", "application/json")
        assertEquals(emptyList<String>(), headers.getAll("Accept"))
    }

    @Test
    fun `get returns first value when multiple exist`() {
        val headers = headersOf("Accept" to listOf("text/html", "application/json"))
        assertEquals("text/html", headers["Accept"])
    }

    @Test
    fun `build creates headers via builder`() {
        val headers = Headers.build {
            append("Content-Type", "application/json")
            append("Accept", "text/html")
        }
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("text/html", headers["Accept"])
    }

    @Test
    fun `build appends multiple values for same header`() {
        val headers = Headers.build {
            append("Set-Cookie", "a=1")
            append("Set-Cookie", "b=2")
        }
        val all = headers.getAll("Set-Cookie")
        assertNotNull(all)
        assertEquals(2, all.size)
        assertEquals("a=1", all[0])
        assertEquals("b=2", all[1])
    }

    @Test
    fun `builder preserves original case for iteration`() {
        val headers = Headers.build {
            append("X-Custom-Header", "value1")
        }
        assertTrue(headers.names().contains("X-Custom-Header"))
    }

    @Test
    fun `builder case-insensitive lookup`() {
        val headers = Headers.build {
            append("X-Custom-Header", "value1")
        }
        assertEquals("value1", headers["x-custom-header"])
        assertEquals("value1", headers["X-CUSTOM-HEADER"])
    }

    @Test
    fun `forEach iterates over all headers`() {
        val headers = headersOf(
            "A" to listOf("1"),
            "B" to listOf("2", "3")
        )
        val collected = mutableMapOf<String, List<String>>()
        headers.forEach { name, values -> collected[name] = values }
        assertEquals(2, collected.size)
        assertEquals(listOf("1"), collected["A"])
        assertEquals(listOf("2", "3"), collected["B"])
    }

    @Test
    fun `isEmpty returns false for non-empty headers`() {
        val headers = headersOf("A", "1")
        assertTrue(!headers.isEmpty())
    }

    @Test
    fun `build with no appends creates empty headers`() {
        val headers = Headers.build {}
        assertTrue(headers.isEmpty())
    }
}

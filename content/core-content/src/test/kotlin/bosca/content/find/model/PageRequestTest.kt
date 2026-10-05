package bosca.content.find.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PageRequestTest {

    // --- offset calculation ---

    @Test
    fun `offset is zero for first page`() {
        val request = PageRequest(pageSize = 10, pageNumber = 0)
        assertEquals(0L, request.offset)
    }

    @Test
    fun `offset is correctly calculated for page 3 with size 25`() {
        val request = PageRequest(pageSize = 25, pageNumber = 3)
        assertEquals(75L, request.offset)
    }

    @Test
    fun `offset handles large page numbers without overflow`() {
        val request = PageRequest(pageSize = 1000, pageNumber = 2_000_000)
        assertEquals(2_000_000_000L, request.offset)
    }

    // --- nextPage ---

    @Test
    fun `nextPage increments page number by one`() {
        val request = PageRequest(pageSize = 10, pageNumber = 2)
        val next = request.nextPage()
        assertEquals(3, next.pageNumber)
        assertEquals(10, next.pageSize)
    }

    // --- previousPage ---

    @Test
    fun `previousPage decrements page number by one`() {
        val request = PageRequest(pageSize = 10, pageNumber = 2)
        val prev = request.previousPage()
        assertNotNull(prev)
        assertEquals(1, prev.pageNumber)
    }

    @Test
    fun `previousPage returns null for first page`() {
        val request = PageRequest(pageSize = 10, pageNumber = 0)
        assertNull(request.previousPage())
    }

    @Test
    fun `previousPage returns request for page 1`() {
        val request = PageRequest(pageSize = 10, pageNumber = 1)
        val prev = request.previousPage()
        assertNotNull(prev)
        assertEquals(0, prev.pageNumber)
    }

    // --- validation ---

    @Test
    fun `page size below minimum throws`() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(pageSize = 0)
        }
    }

    @Test
    fun `page size above maximum throws`() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(pageSize = 1001)
        }
    }

    @Test
    fun `negative page number throws`() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(pageNumber = -1)
        }
    }

    @Test
    fun `minimum page size is accepted`() {
        val request = PageRequest(pageSize = 1)
        assertEquals(1, request.pageSize)
    }

    @Test
    fun `maximum page size is accepted`() {
        val request = PageRequest(pageSize = 1000)
        assertEquals(1000, request.pageSize)
    }

    // --- defaults ---

    @Test
    fun `default page size is 50`() {
        val request = PageRequest()
        assertEquals(50, request.pageSize)
    }

    @Test
    fun `default page number is 0`() {
        val request = PageRequest()
        assertEquals(0, request.pageNumber)
    }

    @Test
    fun `default page token is null`() {
        val request = PageRequest()
        assertNull(request.pageToken)
    }

    // --- generateToken and parsePageToken ---

    @Test
    fun `generateToken produces valid base64 string`() {
        val request = PageRequest(pageSize = 20, pageNumber = 5)
        val token = request.generateToken()
        assertNotNull(token)
    }

    @Test
    fun `parsePageToken round-trips with generateToken`() {
        val request = PageRequest(pageSize = 20, pageNumber = 5)
        val token = request.generateToken()
        val parsed = parsePageToken(token)
        assertNotNull(parsed)
        assertEquals(5, parsed.first)
        assertEquals(20, parsed.second)
    }

    @Test
    fun `parsePageToken returns null for invalid token`() {
        assertNull(parsePageToken("not-valid-base64!@#"))
    }

    @Test
    fun `parsePageToken returns null for malformed content`() {
        val token = java.util.Base64.getEncoder().encodeToString("no-colon".toByteArray())
        assertNull(parsePageToken(token))
    }

    @Test
    fun `parsePageToken returns null for non-numeric parts`() {
        val token = java.util.Base64.getEncoder().encodeToString("abc:def".toByteArray())
        assertNull(parsePageToken(token))
    }
}

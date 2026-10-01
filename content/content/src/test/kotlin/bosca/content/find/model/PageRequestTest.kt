package bosca.content.find.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PageRequestTest {

    @Test
    fun defaultValues() {
        val request = PageRequest()
        assertEquals(50, request.pageSize)
        assertEquals(0, request.pageNumber)
        assertNull(request.pageToken)
    }

    @Test
    fun offsetCalculation() {
        val request = PageRequest(pageSize = 20, pageNumber = 3)
        assertEquals(60L, request.offset)
    }

    @Test
    fun nextPageIncrementsPageNumber() {
        val request = PageRequest(pageSize = 10, pageNumber = 2)
        val next = request.nextPage()
        assertEquals(3, next.pageNumber)
        assertEquals(10, next.pageSize)
    }

    @Test
    fun previousPageAtPageZeroReturnsNull() {
        val request = PageRequest(pageNumber = 0)
        assertNull(request.previousPage())
    }

    @Test
    fun previousPageAtPageTwoReturnsPageOne() {
        val request = PageRequest(pageNumber = 2)
        val previous = request.previousPage()
        assertNotNull(previous)
        assertEquals(1, previous.pageNumber)
    }

    @Test
    fun invalidPageSizeZeroThrows() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(pageSize = 0)
        }
    }

    @Test
    fun invalidPageSizeNegativeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(pageSize = -1)
        }
    }

    @Test
    fun invalidPageSizeExceedsMaxThrows() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(pageSize = 1001)
        }
    }

    @Test
    fun invalidPageNumberNegativeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(pageNumber = -1)
        }
    }

    @Test
    fun generateTokenAndParseTokenRoundTrip() {
        val request = PageRequest(pageSize = 25, pageNumber = 7)
        val token = request.generateToken()
        val parsed = parsePageToken(token)
        assertNotNull(parsed)
        assertEquals(7, parsed.first)
        assertEquals(25, parsed.second)
    }

    @Test
    fun parsePageTokenWithInvalidTokenReturnsNull() {
        assertNull(parsePageToken("not-valid-base64!!!"))
        assertNull(parsePageToken(java.util.Base64.getEncoder().encodeToString("invalid".toByteArray())))
    }
}

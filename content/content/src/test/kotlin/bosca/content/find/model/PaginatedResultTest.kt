package bosca.content.find.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaginatedResultTest {

    @Test
    fun totalPagesExactDivision() {
        val result = PaginatedResult(
            items = List(10) { it },
            totalCount = 100,
            pageSize = 10,
            currentPage = 0,
            hasNextPage = true
        )
        assertEquals(10, result.totalPages)
    }

    @Test
    fun totalPagesCeilingDivision() {
        val result = PaginatedResult(
            items = List(10) { it },
            totalCount = 101,
            pageSize = 10,
            currentPage = 0,
            hasNextPage = true
        )
        assertEquals(11, result.totalPages)
    }

    @Test
    fun hasPreviousPageFalseOnPageZero() {
        val result = PaginatedResult(
            items = listOf(1),
            totalCount = 10,
            pageSize = 5,
            currentPage = 0,
            hasNextPage = true
        )
        assertFalse(result.hasPreviousPage)
    }

    @Test
    fun hasPreviousPageTrueOnPageOne() {
        val result = PaginatedResult(
            items = listOf(1),
            totalCount = 10,
            pageSize = 5,
            currentPage = 1,
            hasNextPage = false
        )
        assertTrue(result.hasPreviousPage)
    }

    @Test
    fun nextPageReturnsCurrentPagePlusOneWhenHasNextPage() {
        val result = PaginatedResult(
            items = listOf(1),
            totalCount = 20,
            pageSize = 5,
            currentPage = 2,
            hasNextPage = true
        )
        assertEquals(3, result.nextPage)
    }

    @Test
    fun nextPageReturnsNullWhenNoNextPage() {
        val result = PaginatedResult(
            items = listOf(1),
            totalCount = 10,
            pageSize = 5,
            currentPage = 1,
            hasNextPage = false
        )
        assertNull(result.nextPage)
    }

    @Test
    fun previousPageReturnsNullOnPageZero() {
        val result = PaginatedResult(
            items = listOf(1),
            totalCount = 10,
            pageSize = 5,
            currentPage = 0,
            hasNextPage = true
        )
        assertNull(result.previousPage)
    }

    @Test
    fun invalidTotalCountThrows() {
        assertFailsWith<IllegalArgumentException> {
            PaginatedResult(
                items = emptyList<Int>(),
                totalCount = -1,
                pageSize = 10,
                currentPage = 0,
                hasNextPage = false
            )
        }
    }

    @Test
    fun invalidPageSizeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PaginatedResult(
                items = emptyList<Int>(),
                totalCount = 10,
                pageSize = 0,
                currentPage = 0,
                hasNextPage = false
            )
        }
    }

    @Test
    fun itemsSizeExceedsPageSizeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PaginatedResult(
                items = List(6) { it },
                totalCount = 10,
                pageSize = 5,
                currentPage = 0,
                hasNextPage = true
            )
        }
    }
}

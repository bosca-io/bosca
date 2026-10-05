package bosca.content.find.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaginatedResultTest {

    // --- totalPages ---

    @Test
    fun `totalPages is correct for exact division`() {
        val result = PaginatedResult(
            items = listOf("a", "b"),
            totalCount = 10,
            pageSize = 5,
            currentPage = 0,
            hasNextPage = true
        )
        assertEquals(2, result.totalPages)
    }

    @Test
    fun `totalPages rounds up for partial page`() {
        val result = PaginatedResult(
            items = listOf("a"),
            totalCount = 11,
            pageSize = 5,
            currentPage = 0,
            hasNextPage = true
        )
        assertEquals(3, result.totalPages)
    }

    @Test
    fun `totalPages is zero when totalCount is zero`() {
        val result = PaginatedResult(
            items = emptyList<String>(),
            totalCount = 0,
            pageSize = 10,
            currentPage = 0,
            hasNextPage = false
        )
        assertEquals(0, result.totalPages)
    }

    @Test
    fun `totalPages is 1 for single item`() {
        val result = PaginatedResult(
            items = listOf("a"),
            totalCount = 1,
            pageSize = 10,
            currentPage = 0,
            hasNextPage = false
        )
        assertEquals(1, result.totalPages)
    }

    // --- hasPreviousPage ---

    @Test
    fun `hasPreviousPage is false on first page`() {
        val result = PaginatedResult(
            items = emptyList<String>(),
            totalCount = 100,
            pageSize = 10,
            currentPage = 0,
            hasNextPage = true
        )
        assertFalse(result.hasPreviousPage)
    }

    @Test
    fun `hasPreviousPage is true on second page`() {
        val result = PaginatedResult(
            items = emptyList<String>(),
            totalCount = 100,
            pageSize = 10,
            currentPage = 1,
            hasNextPage = true
        )
        assertTrue(result.hasPreviousPage)
    }

    // --- nextPage ---

    @Test
    fun `nextPage returns next page number when hasNextPage`() {
        val result = PaginatedResult(
            items = emptyList<String>(),
            totalCount = 100,
            pageSize = 10,
            currentPage = 2,
            hasNextPage = true
        )
        assertEquals(3, result.nextPage)
    }

    @Test
    fun `nextPage returns null when no next page`() {
        val result = PaginatedResult(
            items = emptyList<String>(),
            totalCount = 10,
            pageSize = 10,
            currentPage = 0,
            hasNextPage = false
        )
        assertNull(result.nextPage)
    }

    // --- previousPage ---

    @Test
    fun `previousPage returns previous page number`() {
        val result = PaginatedResult(
            items = emptyList<String>(),
            totalCount = 100,
            pageSize = 10,
            currentPage = 3,
            hasNextPage = true
        )
        assertEquals(2, result.previousPage)
    }

    @Test
    fun `previousPage returns null on first page`() {
        val result = PaginatedResult(
            items = emptyList<String>(),
            totalCount = 100,
            pageSize = 10,
            currentPage = 0,
            hasNextPage = true
        )
        assertNull(result.previousPage)
    }

    // --- validation ---

    @Test
    fun `negative totalCount throws`() {
        assertFailsWith<IllegalArgumentException> {
            PaginatedResult(
                items = emptyList<String>(),
                totalCount = -1,
                pageSize = 10,
                currentPage = 0,
                hasNextPage = false
            )
        }
    }

    @Test
    fun `zero pageSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            PaginatedResult(
                items = emptyList<String>(),
                totalCount = 0,
                pageSize = 0,
                currentPage = 0,
                hasNextPage = false
            )
        }
    }

    @Test
    fun `negative currentPage throws`() {
        assertFailsWith<IllegalArgumentException> {
            PaginatedResult(
                items = emptyList<String>(),
                totalCount = 0,
                pageSize = 10,
                currentPage = -1,
                hasNextPage = false
            )
        }
    }

    @Test
    fun `items exceeding pageSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            PaginatedResult(
                items = listOf("a", "b", "c"),
                totalCount = 3,
                pageSize = 2,
                currentPage = 0,
                hasNextPage = false
            )
        }
    }
}

package bosca.search.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchQueryTest {

    @Test
    fun `SearchQuery default query is empty string`() {
        val query = SearchQuery(offset = null, limit = null)
        assertEquals("", query.query)
    }

    @Test
    fun `SearchQuery stores query string`() {
        val query = SearchQuery(query = "test search", offset = 0, limit = 10)
        assertEquals("test search", query.query)
    }

    @Test
    fun `SearchQuery offset and limit can be null`() {
        val query = SearchQuery(offset = null, limit = null)
        assertNull(query.offset)
        assertNull(query.limit)
    }

    @Test
    fun `SearchQuery offset and limit store values`() {
        val query = SearchQuery(offset = 20, limit = 10)
        assertEquals(20, query.offset)
        assertEquals(10, query.limit)
    }

    @Test
    fun `SearchQuery facets default to null`() {
        val query = SearchQuery(offset = 0, limit = 10)
        assertNull(query.facets)
    }

    @Test
    fun `SearchQuery with facets`() {
        val query = SearchQuery(offset = 0, limit = 10, facets = listOf("category", "type"))
        assertEquals(listOf("category", "type"), query.facets)
    }

    @Test
    fun `SearchQuery filter defaults to null`() {
        val query = SearchQuery(offset = 0, limit = 10)
        assertNull(query.filter)
    }

    @Test
    fun `SearchQuery with filters`() {
        val query = SearchQuery(
            offset = 0,
            limit = 10,
            filter = listOf("type = article", "status = published")
        )
        assertEquals(2, query.filter!!.size)
    }

    @Test
    fun `SearchQuery sort defaults to null`() {
        val query = SearchQuery(offset = 0, limit = 10)
        assertNull(query.sort)
    }

    @Test
    fun `SearchQuery with sort`() {
        val query = SearchQuery(offset = 0, limit = 10, sort = listOf("created:desc"))
        assertEquals(listOf("created:desc"), query.sort)
    }

    @Test
    fun `SearchQuery semanticRatio defaults to null`() {
        val query = SearchQuery(offset = 0, limit = 10)
        assertNull(query.semanticRatio)
    }

    @Test
    fun `SearchQuery with semanticRatio`() {
        val query = SearchQuery(offset = 0, limit = 10, semanticRatio = 0.7)
        assertEquals(0.7, query.semanticRatio)
    }

    @Test
    fun `SearchQuery vector defaults to null`() {
        val query = SearchQuery(offset = 0, limit = 10)
        assertNull(query.vector)
    }

    @Test
    fun `SearchQuery with vector`() {
        val vector = listOf(0.1, 0.2, 0.3)
        val query = SearchQuery(offset = 0, limit = 10, vector = vector)
        assertEquals(vector, query.vector)
    }

    @Test
    fun `SearchQuery data class equality`() {
        val q1 = SearchQuery(query = "test", offset = 0, limit = 10)
        val q2 = SearchQuery(query = "test", offset = 0, limit = 10)
        assertEquals(q1, q2)
    }

    @Test
    fun `SearchQuery data class copy`() {
        val original = SearchQuery(query = "test", offset = 0, limit = 10)
        val modified = original.copy(query = "modified")
        assertEquals("modified", modified.query)
        assertEquals(0, modified.offset)
    }

    @Test
    fun `SearchQuery storageSystemId defaults to null`() {
        val query = SearchQuery(offset = 0, limit = 10)
        assertNull(query.storageSystemId)
    }

    @Test
    fun `SearchQuery storageSystemName defaults to null`() {
        val query = SearchQuery(offset = 0, limit = 10)
        assertNull(query.storageSystemName)
    }
}

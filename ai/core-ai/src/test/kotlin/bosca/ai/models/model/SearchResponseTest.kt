package bosca.ai.models.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchResponseTest {

    // --- SearchResponse ---

    @Test
    fun `SearchResponse stores all properties`() {
        val hit = Hit(
            id = "1", slug = "test-item", name = "Test",
            itemType = ItemType.METADATA, published = 1000L,
            created = 900L, modified = 1000L,
            collections = listOf(Collection(id = "c1", type = "default", name = "Main"))
        )
        val response = SearchResponse(
            hits = listOf(hit), query = "test",
            limit = 10, offset = 0, estimatedHits = 1
        )
        assertEquals("test", response.query)
        assertEquals(10, response.limit)
        assertEquals(0, response.offset)
        assertEquals(1L, response.estimatedHits)
        assertEquals(1, response.hits.size)
    }

    @Test
    fun `SearchResponse page defaults to null`() {
        val response = SearchResponse(
            hits = emptyList(), query = "q",
            limit = 10, offset = 0, estimatedHits = 0
        )
        assertNull(response.page)
    }

    @Test
    fun `SearchResponse page can be set`() {
        val response = SearchResponse(
            hits = emptyList(), query = "q",
            limit = 10, offset = 0, estimatedHits = 0, page = 3
        )
        assertEquals(3, response.page)
    }

    // --- Hit ---

    @Test
    fun `Hit optional fields default to null`() {
        val hit = Hit(
            id = "1", slug = "s", name = "n",
            itemType = ItemType.COLLECTION, published = 0L,
            created = 0L, modified = 0L, collections = emptyList()
        )
        assertNull(hit.type)
        assertNull(hit.description)
        assertNull(hit.contentType)
        assertNull(hit.categories)
        assertNull(hit.content)
    }

    @Test
    fun `Hit stores optional fields when provided`() {
        val categories = listOf(Category(id = "cat1", name = "News"))
        val hit = Hit(
            id = "1", slug = "s", name = "n",
            itemType = ItemType.METADATA, published = 0L,
            created = 0L, modified = 0L, collections = emptyList(),
            type = "article", description = "desc",
            contentType = "text/html", categories = categories,
            content = "body text"
        )
        assertEquals("article", hit.type)
        assertEquals("desc", hit.description)
        assertEquals("text/html", hit.contentType)
        assertEquals(1, hit.categories!!.size)
        assertEquals("body text", hit.content)
    }

    // --- Category ---

    @Test
    fun `Category stores id and name`() {
        val cat = Category(id = "c1", name = "Science")
        assertEquals("c1", cat.id)
        assertEquals("Science", cat.name)
    }

    // --- Collection ---

    @Test
    fun `Collection stores id type and name`() {
        val col = Collection(id = "col1", type = "folder", name = "Root")
        assertEquals("col1", col.id)
        assertEquals("folder", col.type)
        assertEquals("Root", col.name)
    }
}

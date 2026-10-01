package bosca.content.transformations

import bosca.search.model.SearchDocumentItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchDocumentTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `SearchDocument creation with required fields only`() {
        val doc = SearchDocument(
            id = "doc-1",
            contentId = "content-1",
            slug = "my-doc",
            languageTag = "en",
            name = "My Document",
            documentType = "article",
            contentType = "text/plain"
        )
        assertEquals("doc-1", doc.id)
        assertEquals("content-1", doc.contentId)
        assertEquals("my-doc", doc.slug)
        assertEquals("en", doc.languageTag)
        assertEquals("My Document", doc.name)
        assertEquals("article", doc.documentType)
        assertEquals("text/plain", doc.contentType)
    }

    @Test
    fun `SearchDocument optional fields default correctly`() {
        val doc = SearchDocument(
            id = "1",
            contentId = "c1",
            slug = "s",
            languageTag = "en",
            name = "n",
            documentType = "t",
            contentType = "ct"
        )
        assertNull(doc.description)
        assertTrue(doc.labels.isEmpty())
        assertEquals(0L, doc.published)
        assertEquals(0L, doc.created)
        assertEquals(0L, doc.modified)
        assertTrue(doc.categories.isEmpty())
        assertNull(doc.content)
    }

    @Test
    fun `SearchDocument creation with all fields`() {
        val categories = listOf(SearchDocumentItem(id = "cat1", name = "Category 1"))
        val doc = SearchDocument(
            id = "doc-2",
            contentId = "content-2",
            slug = "full-doc",
            languageTag = "fr",
            name = "Full Document",
            description = "A detailed description",
            labels = listOf("label1", "label2"),
            documentType = "guide",
            contentType = "application/json",
            published = 1000L,
            created = 900L,
            modified = 1100L,
            categories = categories,
            content = "Some body content"
        )
        assertEquals("A detailed description", doc.description)
        assertEquals(listOf("label1", "label2"), doc.labels)
        assertEquals(1000L, doc.published)
        assertEquals(900L, doc.created)
        assertEquals(1100L, doc.modified)
        assertEquals(1, doc.categories.size)
        assertEquals("cat1", doc.categories[0].id)
        assertEquals("Category 1", doc.categories[0].name)
        assertEquals("Some body content", doc.content)
    }

    @Test
    fun `SearchDocument serialization round-trip`() {
        val original = SearchDocument(
            id = "rt-1",
            contentId = "c-rt",
            slug = "round-trip",
            languageTag = "en",
            name = "Round Trip Test",
            description = "Testing serialization",
            labels = listOf("test"),
            documentType = "article",
            contentType = "text/html",
            published = 500L,
            created = 400L,
            modified = 600L,
            categories = listOf(SearchDocumentItem(id = "c1", name = "Cat")),
            content = "Hello world"
        )
        val serialized = json.encodeToString(SearchDocument.serializer(), original)
        val deserialized = json.decodeFromString(SearchDocument.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `SearchDocument serialization uses _type for documentType`() {
        val doc = SearchDocument(
            id = "1",
            contentId = "c1",
            slug = "s",
            languageTag = "en",
            name = "n",
            documentType = "myType",
            contentType = "ct"
        )
        val serialized = json.encodeToString(SearchDocument.serializer(), doc)
        assertTrue(serialized.contains("\"_type\""), "Serialized JSON should use @SerialName '_type' for documentType")
        assertTrue(serialized.contains("\"myType\""))
    }

    @Test
    fun `SearchDocument data class equality`() {
        val a = SearchDocument(
            id = "eq",
            contentId = "c",
            slug = "s",
            languageTag = "en",
            name = "name",
            documentType = "t",
            contentType = "ct"
        )
        val b = a.copy()
        assertEquals(a, b)
    }

    @Test
    fun `SearchDocument copy changes single field`() {
        val original = SearchDocument(
            id = "1",
            contentId = "c",
            slug = "s",
            languageTag = "en",
            name = "Original",
            documentType = "t",
            contentType = "ct"
        )
        val modified = original.copy(name = "Modified")
        assertEquals("Modified", modified.name)
        assertEquals("1", modified.id)
    }
}

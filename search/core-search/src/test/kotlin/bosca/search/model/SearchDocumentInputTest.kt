package bosca.search.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SearchDocumentInputTest {

    @Test
    fun `SearchDocumentInput stores all fields`() {
        val id = Uuid.random()
        val input = SearchDocumentInput(
            id = id,
            type = SearchDocumentType.METADATA,
            title = "Test Document",
            content = "Some content"
        )
        assertEquals(id, input.id)
        assertEquals(SearchDocumentType.METADATA, input.type)
        assertEquals("Test Document", input.title)
        assertEquals("Some content", input.content)
    }

    @Test
    fun `SearchDocumentInput content defaults to null`() {
        val input = SearchDocumentInput(
            id = Uuid.random(),
            type = SearchDocumentType.COLLECTION,
            title = "Test"
        )
        assertNull(input.content)
    }

    @Test
    fun `SearchDocumentInput attributes defaults to null`() {
        val input = SearchDocumentInput(
            id = Uuid.random(),
            type = SearchDocumentType.COLLECTION,
            title = "Test"
        )
        assertNull(input.attributes)
    }

    @Test
    fun `toJsonElement includes id, type, title, and content`() {
        val id = Uuid.random()
        val input = SearchDocumentInput(
            id = id,
            type = SearchDocumentType.METADATA,
            title = "My Title",
            content = "My Content"
        )
        val json = input.toJsonElement().jsonObject
        assertEquals(id.toString(), json["id"]?.let { (it as JsonPrimitive).content })
        assertEquals("metadata", json["type"]?.let { (it as JsonPrimitive).content })
        assertEquals("My Title", json["title"]?.let { (it as JsonPrimitive).content })
        assertEquals("My Content", json["content"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `toJsonElement with null content includes null value`() {
        val input = SearchDocumentInput(
            id = Uuid.random(),
            type = SearchDocumentType.PROFILE,
            title = "Profile"
        )
        val json = input.toJsonElement().jsonObject
        assertTrue(json.containsKey("content"))
    }

    @Test
    fun `toJsonElement merges attributes into document`() {
        val input = SearchDocumentInput(
            id = Uuid.random(),
            type = SearchDocumentType.METADATA,
            title = "Test",
            attributes = JsonObject(mapOf(
                "category" to JsonPrimitive("news"),
                "tags" to JsonPrimitive("test")
            ))
        )
        val json = input.toJsonElement().jsonObject
        assertEquals("news", (json["category"] as JsonPrimitive).content)
        assertEquals("test", (json["tags"] as JsonPrimitive).content)
    }

    @Test
    fun `toJsonElement ignores null attributes`() {
        val input = SearchDocumentInput(
            id = Uuid.random(),
            type = SearchDocumentType.METADATA,
            title = "Test",
            attributes = null
        )
        val json = input.toJsonElement().jsonObject
        assertEquals(4, json.size) // id, type, title, content
    }

    @Test
    fun `toJsonElement ignores JsonNull attributes`() {
        val input = SearchDocumentInput(
            id = Uuid.random(),
            type = SearchDocumentType.METADATA,
            title = "Test",
            attributes = JsonNull
        )
        val json = input.toJsonElement().jsonObject
        assertEquals(4, json.size) // id, type, title, content
    }

    @Test
    fun `toJsonElement type is lowercase of enum name`() {
        for (type in SearchDocumentType.entries) {
            val input = SearchDocumentInput(
                id = Uuid.random(),
                type = type,
                title = "Test"
            )
            val json = input.toJsonElement().jsonObject
            assertEquals(type.name.lowercase(), (json["type"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `SearchResultFacet stores count, field, and value`() {
        val facet = SearchResultFacet(count = 42, field = "category", value = "news")
        assertEquals(42, facet.count)
        assertEquals("category", facet.field)
        assertEquals("news", facet.value)
    }
}

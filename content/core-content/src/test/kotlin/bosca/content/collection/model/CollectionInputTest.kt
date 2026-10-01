package bosca.content.collection.model

import bosca.content.ordering.OrderingInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CollectionInputTest {

    @Test
    fun `CollectionInput stores required name field`() {
        val input = CollectionInput(name = "Test Collection")
        assertEquals("Test Collection", input.name)
    }

    @Test
    fun `CollectionInput nullable fields default to null`() {
        val input = CollectionInput(name = "c")
        assertNull(input.description)
        assertNull(input.languageTag)
        assertNull(input.collectionType)
        assertNull(input.systemAttributes)
        assertNull(input.categoryIds)
        assertNull(input.slug)
        assertNull(input.locked)
        assertNull(input.labels)
        assertNull(input.ready)
        assertNull(input.deleteWorkflowId)
        assertNull(input.traitIds)
        assertNull(input.itemsLocked)
        assertNull(input.parentCollectionId)
        assertNull(input.templateMetadataId)
        assertNull(input.templateMetadataVersion)
        assertNull(input.ordering)
        assertNull(input.collections)
        assertNull(input.metadata)
        assertNull(input.state)
    }

    @Test
    fun `CollectionInput attributes defaults to JsonNull`() {
        val input = CollectionInput(name = "c")
        assertEquals(JsonNull, input.attributes)
    }

    @Test
    fun `CollectionInput boolean defaults`() {
        val input = CollectionInput(name = "c")
        assertFalse(input.public)
        assertFalse(input.publicList)
        assertFalse(input.publicSupplementary)
        assertTrue(input.searchable)
        assertTrue(input.recommendable)
    }

    @Test
    fun `CollectionInput stores all properties`() {
        val parentId = Uuid.random()
        val templateId = Uuid.random()
        val catId = Uuid.random()
        val attrs = buildJsonObject { put("key", "value") }
        val sysAttrs = buildJsonObject { put("sys", true) }
        val ordering = listOf(OrderingInput(field = "name"))
        val child = CollectionChildInput()
        val metaChild = MetadataChildInput()
        val state = CollectionWorkflowInput(state = "published")

        val input = CollectionInput(
            name = "My Collection",
            description = "A description",
            languageTag = "en",
            collectionType = CollectionType.FOLDER,
            attributes = attrs,
            systemAttributes = sysAttrs,
            categoryIds = listOf(catId),
            slug = "my-collection",
            locked = true,
            labels = listOf("label1", "label2"),
            deleteWorkflowId = "delete-wf",
            traitIds = listOf("trait-1"),
            itemsLocked = true,
            parentCollectionId = parentId,
            templateMetadataId = templateId,
            templateMetadataVersion = 3,
            ordering = ordering,
            collections = listOf(child),
            metadata = listOf(metaChild),
            state = state,
            public = true,
            publicList = true,
            publicSupplementary = true,
            searchable = false,
            recommendable = false,
        )

        assertEquals("My Collection", input.name)
        assertEquals("A description", input.description)
        assertEquals("en", input.languageTag)
        assertEquals(CollectionType.FOLDER, input.collectionType)
        assertEquals(attrs, input.attributes)
        assertEquals(sysAttrs, input.systemAttributes)
        assertEquals(listOf(catId), input.categoryIds)
        assertEquals("my-collection", input.slug)
        assertEquals(true, input.locked)
        assertEquals(listOf("label1", "label2"), input.labels)
        assertEquals("delete-wf", input.deleteWorkflowId)
        assertEquals(listOf("trait-1"), input.traitIds)
        assertEquals(true, input.itemsLocked)
        assertEquals(parentId, input.parentCollectionId)
        assertEquals(templateId, input.templateMetadataId)
        assertEquals(3, input.templateMetadataVersion)
        assertEquals(1, input.ordering!!.size)
        assertEquals(1, input.collections!!.size)
        assertEquals(1, input.metadata!!.size)
        assertEquals("published", input.state!!.state)
        assertTrue(input.public)
        assertTrue(input.publicList)
        assertTrue(input.publicSupplementary)
        assertFalse(input.searchable)
        assertFalse(input.recommendable)
    }

    @Test
    fun `CollectionInput data class equality`() {
        val attrs = buildJsonObject { put("k", "v") }
        val input1 = CollectionInput(name = "c", attributes = attrs)
        val input2 = CollectionInput(name = "c", attributes = attrs)
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `CollectionInput copy preserves unchanged fields`() {
        val input = CollectionInput(
            name = "original",
            description = "desc",
            languageTag = "fr"
        )
        val copied = input.copy(name = "updated")
        assertEquals("updated", copied.name)
        assertEquals("desc", copied.description)
        assertEquals("fr", copied.languageTag)
    }
}

package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MetadataInputTest {

    @Test
    fun `MetadataInput stores required fields`() {
        val input = MetadataInput(
            name = "Test Metadata",
            languageTag = "en",
            contentType = "application/json"
        )
        assertEquals("Test Metadata", input.name)
        assertEquals("en", input.languageTag)
        assertEquals("application/json", input.contentType)
    }

    @Test
    fun `MetadataInput nullable fields default to null`() {
        val input = MetadataInput(
            name = "m", languageTag = "en", contentType = "text/plain"
        )
        assertNull(input.contentLength)
        assertNull(input.metadataType)
        assertNull(input.parentId)
        assertNull(input.parentCollectionId)
        assertNull(input.slug)
        assertNull(input.locked)
        assertNull(input.systemAttributes)
        assertNull(input.document)
        assertNull(input.guide)
        assertNull(input.data)
        assertNull(input.labels)
        assertNull(input.traitIds)
        assertNull(input.categoryIds)
        assertNull(input.profiles)
        assertNull(input.source)
        assertNull(input.collectionTemplate)
        assertNull(input.guideTemplate)
        assertNull(input.documentTemplate)
        assertNull(input.dataTemplate)
        assertNull(input.syncVariantCollections)
        assertNull(input.syncVariantRelationships)
        assertNull(input.searchable)
        assertNull(input.recommendable)
    }

    @Test
    fun `MetadataInput attributes defaults to JsonNull`() {
        val input = MetadataInput(
            name = "m", languageTag = "en", contentType = "text/plain"
        )
        assertEquals(JsonNull, input.attributes)
    }

    @Test
    fun `MetadataInput stores all properties`() {
        val parentId = Uuid.random()
        val parentCollectionId = Uuid.random()
        val catId = Uuid.random()
        val attrs = buildJsonObject { put("key", "value") }
        val sysAttrs = buildJsonObject { put("sys", true) }

        val input = MetadataInput(
            name = "Full Metadata",
            languageTag = "fr",
            contentType = "image/png",
            contentLength = 1024L,
            metadataType = MetadataType.VARIANT,
            parentId = parentId,
            parentCollectionId = parentCollectionId,
            slug = "full-metadata",
            locked = true,
            attributes = attrs,
            systemAttributes = sysAttrs,
            labels = listOf("label1"),
            traitIds = listOf("trait-1"),
            categoryIds = listOf(catId),
            syncVariantCollections = false,
            syncVariantRelationships = false,
            searchable = false,
            recommendable = false,
        )

        assertEquals("Full Metadata", input.name)
        assertEquals("fr", input.languageTag)
        assertEquals("image/png", input.contentType)
        assertEquals(1024L, input.contentLength)
        assertEquals(MetadataType.VARIANT, input.metadataType)
        assertEquals(parentId, input.parentId)
        assertEquals(parentCollectionId, input.parentCollectionId)
        assertEquals("full-metadata", input.slug)
        assertEquals(true, input.locked)
        assertEquals(attrs, input.attributes)
        assertEquals(sysAttrs, input.systemAttributes)
        assertEquals(listOf("label1"), input.labels)
        assertEquals(listOf("trait-1"), input.traitIds)
        assertEquals(listOf(catId), input.categoryIds)
        assertEquals(false, input.syncVariantCollections)
        assertEquals(false, input.syncVariantRelationships)
        assertEquals(false, input.searchable)
        assertEquals(false, input.recommendable)
    }

    @Test
    fun `MetadataInput data class equality`() {
        val input1 = MetadataInput(name = "m", languageTag = "en", contentType = "text/plain")
        val input2 = MetadataInput(name = "m", languageTag = "en", contentType = "text/plain")
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `MetadataInput copy preserves unchanged fields`() {
        val input = MetadataInput(
            name = "original",
            languageTag = "en",
            contentType = "text/plain",
            contentLength = 500L
        )
        val copied = input.copy(name = "updated")
        assertEquals("updated", copied.name)
        assertEquals("en", copied.languageTag)
        assertEquals("text/plain", copied.contentType)
        assertEquals(500L, copied.contentLength)
    }

    @Test
    fun `editing preserves recommendable when omitted and applies an explicit value`() {
        val existing = Metadata(
            name = "existing",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
            recommendable = false,
        )
        val input = MetadataInput(name = "updated", languageTag = "en", contentType = "text/plain")

        assertFalse(input.toMetadata(existing).recommendable)
        assertTrue(input.copy(recommendable = true).toMetadata(existing).recommendable)
    }
}

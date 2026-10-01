package bosca.content.collection.model

import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

@OptIn(ExperimentalSerializationApi::class)
class CollectionTest {

    private val id = Uuid.parse("650e8400-e29b-41d4-a716-446655440000")
    private val templateId = Uuid.parse("650e8400-e29b-41d4-a716-446655440001")
    private val timestamp = OffsetDateTime.parse("2026-08-04T12:00:00Z")
    private val serializers = SerializersModule {
        contextual(UUIDSerializer())
        contextual(OffsetDateTimeSerializer())
    }
    private val compactJson = Json {
        encodeDefaults = false
        explicitNulls = false
        serializersModule = serializers
    }
    private val completeJson = Json {
        encodeDefaults = true
        explicitNulls = true
        serializersModule = serializers
    }

    @Test
    fun `minimal collection applies every default`() {
        val collection = Collection(
            name = "Minimal",
            languageTag = "en",
            workflowStateId = "pending",
        )

        assertEquals(Uuid.NIL, collection.id)
        assertEquals(CollectionType.STANDARD, collection.type)
        assertNull(collection.description)
        assertNull(collection.attributes)
        assertNull(collection.systemAttributes)
        assertEquals(emptyList(), collection.labels)
        assertNull(collection.ready)
        assertNull(collection.etag)
        assertTrue(collection.enabled)
        assertNull(collection.ordering)
        assertNull(collection.workflowStatePendingId)
        assertNull(collection.workflowStateValid)
        assertNull(collection.deleteWorkflowId)
        assertFalse(collection.public)
        assertFalse(collection.publicList)
        assertFalse(collection.publicSupplementary)
        assertFalse(collection.locked)
        assertFalse(collection.itemsLocked)
        assertFalse(collection.deleted)
        assertNull(collection.templateMetadataId)
        assertNull(collection.templateMetadataVersion)
        assertTrue(collection.searchable)
        assertTrue(collection.recommendable)
        assertNull(collection.defaultLanguageVariant)
        assertFalse(collection.publicContent)
        assertNull(collection.version)
        assertNull(collection.itemAttributes)
        assertFalse(collection.isPublished)
        assertFalse(collection.isAdvertised)
        assertFalse(collection.isDeleted)
        assertTrue(collection.isSearchable)
        assertTrue(collection.isRecommendable)

        val encoded = compactJson.encodeToString(Collection.serializer(), collection)
        assertEquals(collection, compactJson.decodeFromString(Collection.serializer(), encoded))
    }

    @Test
    fun `collection supports every explicit value and mutable transient properties`() {
        val attributes = JsonPrimitive("attributes")
        val systemAttributes = JsonPrimitive("system")
        val ordering = JsonPrimitive("ordering")
        val collection = Collection(
            id = id,
            name = "Complete",
            languageTag = "fr-CA",
            type = CollectionType.QUEUE,
            description = "Description",
            attributes = attributes,
            systemAttributes = systemAttributes,
            labels = listOf("featured"),
            created = timestamp,
            modified = timestamp.plusHours(1),
            ready = timestamp.plusHours(2),
            etag = "etag",
            enabled = false,
            ordering = ordering,
            workflowStateId = "published",
            workflowStatePendingId = "archived",
            workflowStateValid = timestamp.plusDays(1),
            deleteWorkflowId = "delete-collection",
            public = true,
            publicList = true,
            publicSupplementary = true,
            locked = true,
            itemsLocked = true,
            deleted = true,
            templateMetadataId = templateId,
            templateMetadataVersion = 4,
            recommendable = false,
        )
        val variant = CollectionLanguageVariant(id, "fr-CA", "Variant")
        collection.defaultLanguageVariant = variant
        collection.itemAttributes = JsonPrimitive("item")

        assertEquals(id, collection.id)
        assertEquals(attributes, collection.attributes)
        assertEquals(systemAttributes, collection.systemAttributes)
        assertEquals(ordering, collection.ordering)
        assertEquals(templateId, collection.templateMetadataId)
        assertSame(variant, collection.defaultLanguageVariant)
        assertEquals(JsonPrimitive("item"), collection.itemAttributes)
        assertTrue(collection.isPublished)
        assertFalse(collection.isAdvertised)
        assertTrue(collection.isDeleted)
        assertTrue(collection.isSearchable)
        assertFalse(collection.isRecommendable)

        val encoded = completeJson.encodeToString(Collection.serializer(), collection)
        assertEquals(collection, completeJson.decodeFromString(Collection.serializer(), encoded))
    }

    @Test
    fun `collection explicit final default field and advertised state are reachable`() {
        val collection = Collection(
            id = id,
            name = "Advertised",
            languageTag = "en-US",
            type = CollectionType.FOLDER,
            description = null,
            attributes = null,
            systemAttributes = null,
            labels = emptyList(),
            created = timestamp,
            modified = timestamp,
            ready = null,
            etag = null,
            enabled = true,
            ordering = null,
            workflowStateId = "advertised",
            workflowStatePendingId = null,
            workflowStateValid = null,
            deleteWorkflowId = null,
            public = false,
            publicList = false,
            publicSupplementary = false,
            locked = false,
            itemsLocked = false,
            deleted = false,
            templateMetadataId = null,
            searchable = false,
        )

        assertFalse(collection.isPublished)
        assertTrue(collection.isAdvertised)
        assertFalse(collection.isSearchable)
    }

    @Test
    fun `minimal language variant applies every default`() {
        val variant = CollectionLanguageVariant(id, "en", "Minimal")

        assertNull(variant.description)
        assertNull(variant.attributes)
        assertNull(variant.ready)
        assertEquals("pending", variant.workflowStateId)
        assertNull(variant.workflowStatePendingId)
        assertNull(variant.workflowStateValid)
        assertNull(variant.deleteWorkflowId)
        assertFalse(variant.public)
        assertFalse(variant.publicList)
        assertFalse(variant.publicSupplementary)
        assertTrue(variant.searchable)
        assertTrue(variant.recommendable)
        assertFalse(variant.publicContent)
        assertNull(variant.version)
        assertNull(variant.itemAttributes)
        assertFalse(variant.isPublished)
        assertFalse(variant.isAdvertised)
        assertFalse(variant.isDeleted)
        assertTrue(variant.isSearchable)
        assertTrue(variant.isRecommendable)

        val encoded = compactJson.encodeToString(CollectionLanguageVariant.serializer(), variant)
        assertEquals(variant, compactJson.decodeFromString(CollectionLanguageVariant.serializer(), encoded))
    }

    @Test
    fun `language variant supports every explicit value and mutable item attributes`() {
        val variant = CollectionLanguageVariant(
            id = id,
            languageTag = "fr-CA",
            name = "Complete",
            description = "Description",
            attributes = JsonPrimitive("attributes"),
            ready = timestamp,
            workflowStateId = "published",
            workflowStatePendingId = "archived",
            workflowStateValid = timestamp.plusDays(1),
            deleteWorkflowId = "delete-variant",
            public = true,
            publicList = true,
            publicSupplementary = true,
            recommendable = false,
        )
        variant.itemAttributes = JsonPrimitive("item")

        assertTrue(variant.isPublished)
        assertFalse(variant.isAdvertised)
        assertEquals(JsonPrimitive("item"), variant.itemAttributes)
        assertTrue(variant.isSearchable)
        assertFalse(variant.isRecommendable)

        val encoded = completeJson.encodeToString(CollectionLanguageVariant.serializer(), variant)
        assertEquals(variant, completeJson.decodeFromString(CollectionLanguageVariant.serializer(), encoded))
    }

    @Test
    fun `language variant explicit final default field and advertised state are reachable`() {
        val variant = CollectionLanguageVariant(
            id = id,
            languageTag = "en-US",
            name = "Advertised",
            description = null,
            attributes = null,
            ready = null,
            workflowStateId = "advertised",
            workflowStatePendingId = null,
            workflowStateValid = null,
            public = false,
            publicList = false,
            publicSupplementary = false,
            searchable = false,
        )

        assertFalse(variant.isPublished)
        assertTrue(variant.isAdvertised)
        assertFalse(variant.isSearchable)
    }
}

package bosca.content.metadata.model

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
class MetadataTest {

    private val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val parentId = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")
    private val sourceId = Uuid.parse("550e8400-e29b-41d4-a716-446655440002")
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
    fun `minimal metadata applies every default and exposes derived state`() {
        val metadata = Metadata(
            name = "Minimal",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
        )

        assertEquals(Uuid.NIL, metadata.id)
        assertEquals(1, metadata.version)
        assertEquals(1, metadata.activeVersion)
        assertNull(metadata.parentId)
        assertEquals(emptyList(), metadata.labels)
        assertNull(metadata.attributes)
        assertNull(metadata.systemAttributes)
        assertFalse(metadata.deleted)
        assertFalse(metadata.public)
        assertFalse(metadata.publicContent)
        assertFalse(metadata.publicSupplementary)
        assertNull(metadata.modified)
        assertNull(metadata.uploaded)
        assertNull(metadata.ready)
        assertNull(metadata.workflowStatePendingId)
        assertNull(metadata.workflowStateValid)
        assertNull(metadata.sourceId)
        assertNull(metadata.sourceIdentifier)
        assertNull(metadata.sourceUrl)
        assertNull(metadata.sourceStatus)
        assertNull(metadata.deleteWorkflowId)
        assertEquals(0, metadata.permissionMutation)
        assertNull(metadata.etag)
        assertFalse(metadata.locked)
        assertTrue(metadata.syncVariantCollections)
        assertTrue(metadata.syncVariantRelationships)
        assertTrue(metadata.searchable)
        assertTrue(metadata.recommendable)
        assertFalse(metadata.commentsEnabled)
        assertFalse(metadata.commentRepliesEnabled)
        assertFalse(metadata.publicList)
        assertFalse(metadata.isPublished)
        assertFalse(metadata.isAdvertised)
        assertFalse(metadata.isDeleted)
        assertTrue(metadata.isSearchable)
        assertTrue(metadata.isRecommendable)
        assertNull(metadata.itemAttributes)

        val encoded = compactJson.encodeToString(Metadata.serializer(), metadata)
        assertEquals(metadata, compactJson.decodeFromString(Metadata.serializer(), encoded))
    }

    @Test
    fun `metadata supports every explicit value and mutable item attributes`() {
        val attributes = JsonPrimitive("attributes")
        val systemAttributes = JsonPrimitive("system")
        val metadata = Metadata(
            id = id,
            version = 7,
            activeVersion = 6,
            parentId = parentId,
            name = "Complete",
            type = MetadataType.VARIANT,
            contentType = "application/json",
            contentLength = 4096,
            languageTag = "fr-CA",
            labels = listOf("featured", "translated"),
            attributes = attributes,
            systemAttributes = systemAttributes,
            deleted = true,
            public = true,
            publicContent = true,
            publicSupplementary = true,
            created = timestamp,
            modified = timestamp.plusHours(1),
            uploaded = timestamp.plusHours(2),
            ready = timestamp.plusHours(3),
            workflowStateId = "published",
            workflowStatePendingId = "archived",
            workflowStateValid = timestamp.plusDays(1),
            sourceId = sourceId,
            sourceIdentifier = "source-key",
            sourceUrl = "https://example.test/source",
            sourceStatus = SourceStatus.IMPORTED,
            deleteWorkflowId = "delete-content",
            permissionMutation = 9,
            etag = "etag-value",
            locked = true,
            syncVariantCollections = false,
            syncVariantRelationships = false,
            searchable = false,
            recommendable = false,
            commentsEnabled = true,
        )
        metadata.itemAttributes = JsonPrimitive("item")

        assertEquals(id, metadata.id)
        assertEquals(parentId, metadata.parentId)
        assertEquals(attributes, metadata.attributes)
        assertEquals(systemAttributes, metadata.systemAttributes)
        assertEquals(sourceId, metadata.sourceId)
        assertEquals(SourceStatus.IMPORTED, metadata.sourceStatus)
        assertTrue(metadata.isPublished)
        assertFalse(metadata.isAdvertised)
        assertTrue(metadata.isDeleted)
        assertFalse(metadata.isSearchable)
        assertFalse(metadata.isRecommendable)
        assertEquals(JsonPrimitive("item"), metadata.itemAttributes)

        val encoded = completeJson.encodeToString(Metadata.serializer(), metadata)
        assertEquals(metadata, completeJson.decodeFromString(Metadata.serializer(), encoded))
    }

    @Test
    fun `metadata explicit final default fields and advertised state are reachable`() {
        val metadata = Metadata(
            id = id,
            version = 2,
            activeVersion = 2,
            parentId = null,
            name = "Advertised",
            type = MetadataType.STANDARD,
            contentType = "text/html",
            contentLength = 12,
            languageTag = "en-US",
            labels = emptyList(),
            attributes = null,
            systemAttributes = null,
            deleted = false,
            public = false,
            publicContent = false,
            publicSupplementary = false,
            created = timestamp,
            modified = null,
            uploaded = null,
            ready = null,
            workflowStateId = "advertised",
            workflowStatePendingId = null,
            workflowStateValid = null,
            sourceId = null,
            sourceIdentifier = null,
            sourceUrl = null,
            sourceStatus = null,
            deleteWorkflowId = null,
            permissionMutation = 0,
            locked = false,
            syncVariantCollections = true,
            syncVariantRelationships = true,
            searchable = true,
            commentsEnabled = false,
            commentRepliesEnabled = true,
        )

        assertFalse(metadata.isPublished)
        assertTrue(metadata.isAdvertised)
        assertTrue(metadata.commentRepliesEnabled)
    }

    @Test
    fun `empty metadata is the canonical nil placeholder`() {
        assertSame(EmptyMetadata, EmptyMetadata)
        assertEquals(Uuid.NIL, EmptyMetadata.id)
        assertEquals("unknown", EmptyMetadata.workflowStateId)
    }
}

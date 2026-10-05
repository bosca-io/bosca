package bosca.content.collection.model

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class MetadataRelationshipTest {

    private val sampleMetadata = Metadata(
        id = Uuid.random(),
        name = "Test Metadata",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 100L,
        languageTag = "en",
        workflowStateId = "draft"
    )

    @Test
    fun `stores all properties`() {
        val rel = MetadataRelationship(
            metadata = sampleMetadata,
            relationship = "parent"
        )

        assertEquals(sampleMetadata, rel.metadata)
        assertEquals("parent", rel.relationship)
    }

    @Test
    fun `equality based on all fields`() {
        val a = MetadataRelationship(metadata = sampleMetadata, relationship = "child")
        val b = MetadataRelationship(metadata = sampleMetadata, relationship = "child")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val a = MetadataRelationship(metadata = sampleMetadata, relationship = "parent")
        val b = MetadataRelationship(metadata = sampleMetadata, relationship = "child")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = MetadataRelationship(metadata = sampleMetadata, relationship = "parent")
        val copied = original.copy(relationship = "sibling")
        assertEquals("sibling", copied.relationship)
        assertEquals(original.metadata, copied.metadata)
    }
}

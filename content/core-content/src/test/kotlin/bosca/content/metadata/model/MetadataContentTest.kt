package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MetadataContentTest {

    private fun createMetadata(name: String = "Test") = Metadata(
        id = Uuid.random(),
        name = name,
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 100L,
        languageTag = "en",
        workflowStateId = "draft"
    )

    @Test
    fun `stores metadata and allowed fields`() {
        val metadata = createMetadata()
        val content = MetadataContent(metadata = metadata, allowed = true)
        assertEquals(metadata, content.metadata)
        assertTrue(content.allowed)
    }

    @Test
    fun `allowed defaults to false`() {
        val metadata = createMetadata()
        val content = MetadataContent(metadata = metadata)
        assertFalse(content.allowed)
    }

    @Test
    fun `allowed is mutable`() {
        val metadata = createMetadata()
        val content = MetadataContent(metadata = metadata)
        assertFalse(content.allowed)
        content.allowed = true
        assertTrue(content.allowed)
    }

    @Test
    fun `data class equality`() {
        val metadata = createMetadata()
        val a = MetadataContent(metadata = metadata, allowed = false)
        val b = MetadataContent(metadata = metadata, allowed = false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality when allowed differs`() {
        val metadata = createMetadata()
        val a = MetadataContent(metadata = metadata, allowed = true)
        val b = MetadataContent(metadata = metadata, allowed = false)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy produces modified instance`() {
        val metadata = createMetadata()
        val original = MetadataContent(metadata = metadata, allowed = false)
        val copied = original.copy(allowed = true)
        assertTrue(copied.allowed)
        assertEquals(metadata, copied.metadata)
    }
}

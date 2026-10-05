package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class DocumentInputTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val templateId = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")

    @Test
    fun `DocumentInput stores all properties`() {
        val input = DocumentInput(
            templateMetadataId = templateId,
            templateMetadataVersion = 3,
            title = "My Document",
            content = null
        )
        assertEquals(templateId, input.templateMetadataId)
        assertEquals(3, input.templateMetadataVersion)
        assertEquals("My Document", input.title)
        assertNull(input.content)
    }

    @Test
    fun `DocumentInput template fields default to null`() {
        val input = DocumentInput(
            title = "Simple Document",
            content = null
        )
        assertNull(input.templateMetadataId)
        assertNull(input.templateMetadataVersion)
    }

    @Test
    fun `DocumentInput with null content`() {
        val input = DocumentInput(
            title = "Empty Doc",
            content = null
        )
        assertNull(input.content)
    }

    @Test
    fun `toDocument with id and version maps all fields`() {
        val input = DocumentInput(
            templateMetadataId = templateId,
            templateMetadataVersion = 2,
            title = "Test Title",
            content = null
        )
        val document = input.toDocument(testId, 5)
        assertEquals(testId, document.metadataId)
        assertEquals(5, document.version)
        assertEquals(templateId, document.templateMetadataId)
        assertEquals(2, document.templateMetadataVersion)
        assertEquals("Test Title", document.title)
        assertNull(document.content)
    }

    @Test
    fun `toDocument without template fields`() {
        val input = DocumentInput(
            title = "No Template",
            content = null
        )
        val document = input.toDocument(testId, 1)
        assertEquals(testId, document.metadataId)
        assertEquals(1, document.version)
        assertNull(document.templateMetadataId)
        assertNull(document.templateMetadataVersion)
        assertEquals("No Template", document.title)
    }

    @Test
    fun `toDocument with different ids and versions`() {
        val input = DocumentInput(title = "Doc", content = null)
        val id1 = Uuid.parse("550e8400-e29b-41d4-a716-446655440002")
        val id2 = Uuid.parse("550e8400-e29b-41d4-a716-446655440003")
        val doc1 = input.toDocument(id1, 1)
        val doc2 = input.toDocument(id2, 99)
        assertEquals(id1, doc1.metadataId)
        assertEquals(1, doc1.version)
        assertEquals(id2, doc2.metadataId)
        assertEquals(99, doc2.version)
    }
}

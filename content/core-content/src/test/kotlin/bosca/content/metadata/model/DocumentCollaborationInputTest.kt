package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DocumentCollaborationInputTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `DocumentCollaborationInput stores all properties`() {
        val content = byteArrayOf(10, 20, 30)
        val input = DocumentCollaborationInput(
            metadataId = testId,
            version = 3,
            content = content
        )
        assertEquals(testId, input.metadataId)
        assertEquals(3, input.version)
        assertTrue(content.contentEquals(input.content!!))
    }

    @Test
    fun `DocumentCollaborationInput content defaults to null`() {
        val input = DocumentCollaborationInput(
            metadataId = testId,
            version = 1
        )
        assertEquals(testId, input.metadataId)
        assertEquals(1, input.version)
        assertNull(input.content)
    }

    @Test
    fun `DocumentCollaborationInput with empty byte array`() {
        val input = DocumentCollaborationInput(
            metadataId = testId,
            version = 1,
            content = byteArrayOf()
        )
        assertEquals(0, input.content!!.size)
    }

    @Test
    fun `DocumentCollaborationInput with different versions`() {
        val input1 = DocumentCollaborationInput(metadataId = testId, version = 1)
        val input2 = DocumentCollaborationInput(metadataId = testId, version = 100)
        assertEquals(1, input1.version)
        assertEquals(100, input2.version)
    }

    @Test
    fun `DocumentCollaborationInput with different ids`() {
        val id1 = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")
        val id2 = Uuid.parse("550e8400-e29b-41d4-a716-446655440002")
        val input1 = DocumentCollaborationInput(metadataId = id1, version = 1)
        val input2 = DocumentCollaborationInput(metadataId = id2, version = 1)
        assertEquals(id1, input1.metadataId)
        assertEquals(id2, input2.metadataId)
    }

    @Test
    fun `DocumentCollaborationInput with large content`() {
        val largeContent = ByteArray(1024) { it.toByte() }
        val input = DocumentCollaborationInput(
            metadataId = testId,
            version = 1,
            content = largeContent
        )
        assertEquals(1024, input.content!!.size)
        assertTrue(largeContent.contentEquals(input.content!!))
    }
}

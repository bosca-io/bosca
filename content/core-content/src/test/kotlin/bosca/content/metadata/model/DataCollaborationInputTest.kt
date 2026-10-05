package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DataCollaborationInputTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `DataCollaborationInput stores all properties`() {
        val content = byteArrayOf(1, 2, 3, 4)
        val input = DataCollaborationInput(
            metadataId = testId,
            version = 1,
            content = content
        )
        assertEquals(testId, input.metadataId)
        assertEquals(1, input.version)
        assertTrue(content.contentEquals(input.content!!))
    }

    @Test
    fun `DataCollaborationInput content defaults to null`() {
        val input = DataCollaborationInput(
            metadataId = testId,
            version = 5
        )
        assertEquals(testId, input.metadataId)
        assertEquals(5, input.version)
        assertNull(input.content)
    }

    @Test
    fun `DataCollaborationInput with empty byte array`() {
        val input = DataCollaborationInput(
            metadataId = testId,
            version = 1,
            content = byteArrayOf()
        )
        assertEquals(0, input.content!!.size)
    }

    @Test
    fun `DataCollaborationInput with different versions`() {
        val input1 = DataCollaborationInput(metadataId = testId, version = 1)
        val input2 = DataCollaborationInput(metadataId = testId, version = 42)
        assertEquals(1, input1.version)
        assertEquals(42, input2.version)
    }

    @Test
    fun `DataCollaborationInput with different ids`() {
        val id1 = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")
        val id2 = Uuid.parse("550e8400-e29b-41d4-a716-446655440002")
        val input1 = DataCollaborationInput(metadataId = id1, version = 1)
        val input2 = DataCollaborationInput(metadataId = id2, version = 1)
        assertEquals(id1, input1.metadataId)
        assertEquals(id2, input2.metadataId)
    }
}

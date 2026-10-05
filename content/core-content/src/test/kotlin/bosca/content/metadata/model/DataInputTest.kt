package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class DataInputTest {

    @Test
    fun `DataInput defaults to null`() {
        val input = DataInput()
        assertNull(input.templateMetadataId)
        assertNull(input.templateMetadataVersion)
        assertNull(input.type)
    }

    @Test
    fun `DataInput toData uses default type ATTRIBUTES when type is null`() {
        val id = Uuid.random()
        val input = DataInput()
        val data = input.toData(id, 1)
        assertEquals(id, data.metadataId)
        assertEquals(1, data.version)
        assertEquals(DataType.ATTRIBUTES, data.type)
    }

    @Test
    fun `DataInput toData uses specified type`() {
        val id = Uuid.random()
        val templateId = Uuid.random()
        val input = DataInput(
            templateMetadataId = templateId,
            templateMetadataVersion = 5,
            type = DataType.TABLE
        )
        val data = input.toData(id, 2)
        assertEquals(id, data.metadataId)
        assertEquals(2, data.version)
        assertEquals(templateId, data.templateMetadataId)
        assertEquals(5, data.templateMetadataVersion)
        assertEquals(DataType.TABLE, data.type)
    }
}

package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DataTemplateTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `field preservation after construction with all fields`() {
        val attrs = buildJsonObject { put("key", "value") }
        val template = DataTemplate(
            metadataId = testId,
            version = 1,
            type = DataType.TABLE,
            defaultAttributes = attrs
        )
        assertEquals(testId, template.metadataId)
        assertEquals(1, template.version)
        assertEquals(DataType.TABLE, template.type)
        assertEquals(attrs, template.defaultAttributes)
    }

    @Test
    fun `default values are applied`() {
        val template = DataTemplate(metadataId = testId, version = 1)
        assertEquals(DataType.ATTRIBUTES, template.type)
        assertNull(template.defaultAttributes)
    }

    @Test
    fun `defaultAttributes can be JsonNull`() {
        val template = DataTemplate(
            metadataId = testId,
            version = 1,
            defaultAttributes = JsonNull
        )
        assertEquals(JsonNull, template.defaultAttributes)
    }

    @Test
    fun `defaultAttributes can be null`() {
        val template = DataTemplate(
            metadataId = testId,
            version = 1,
            defaultAttributes = null
        )
        assertNull(template.defaultAttributes)
    }

    @Test
    fun `type TABLE is preserved`() {
        val template = DataTemplate(
            metadataId = testId,
            version = 1,
            type = DataType.TABLE
        )
        assertEquals(DataType.TABLE, template.type)
    }

    @Test
    fun `different versions are preserved`() {
        val t1 = DataTemplate(metadataId = testId, version = 1)
        val t2 = DataTemplate(metadataId = testId, version = 42)
        assertEquals(1, t1.version)
        assertEquals(42, t2.version)
    }
}

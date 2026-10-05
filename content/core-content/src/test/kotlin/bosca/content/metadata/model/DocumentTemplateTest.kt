package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DocumentTemplateTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val config = buildJsonObject { put("key", "value") }
        val schema = buildJsonObject { put("type", "object") }
        val attrs = buildJsonObject { put("attr", "val") }
        val template = DocumentTemplate(
            metadataId = testId,
            version = 3,
            configuration = config,
            schema = schema,
            content = null,
            defaultAttributes = attrs
        )
        assertEquals(testId, template.metadataId)
        assertEquals(3, template.version)
        assertEquals(config, template.configuration)
        assertEquals(schema, template.schema)
        assertNull(template.content)
        assertEquals(attrs, template.defaultAttributes)
    }

    @Test
    fun defaultsAreNull() {
        val template = DocumentTemplate(metadataId = testId, version = 1)
        assertNull(template.configuration)
        assertNull(template.schema)
        assertNull(template.content)
        assertNull(template.defaultAttributes)
    }
}

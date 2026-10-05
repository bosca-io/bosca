package bosca.content.attributes.model

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.metadata.model.DocumentTemplateAttribute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class TemplateAttributeTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun createDocumentAttribute(
        key: String = "title",
        name: String = "Title",
        description: String = "The title field"
    ) = DocumentTemplateAttribute(
        metadataId = testId,
        version = 1,
        key = key,
        name = name,
        description = description
    )

    @Test
    fun `delegates to documentAttribute when set`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertEquals(testId, attr.metadataId)
        assertEquals(1, attr.version)
        assertEquals("title", attr.key)
        assertEquals("Title", attr.name)
        assertEquals("The title field", attr.description)
    }

    @Test
    fun `type defaults to STRING via documentAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertEquals(AttributeType.STRING, attr.type)
    }

    @Test
    fun `ui defaults to INPUT via documentAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertEquals(AttributeUiType.INPUT, attr.ui)
    }

    @Test
    fun `list defaults to false via documentAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertFalse(attr.list)
    }

    @Test
    fun `sort defaults to zero via documentAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertEquals(0, attr.sort)
    }

    @Test
    fun `supplementaryKey is null when not set in documentAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertNull(attr.supplementaryKey)
    }

    @Test
    fun `configuration is null when not set in documentAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertNull(attr.configuration)
    }

    @Test
    fun `tools is null when not set in documentAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertNull(attr.tools)
    }

    @Test
    fun `location defaults to ITEM when no collectionAttribute`() {
        val docAttr = createDocumentAttribute()
        val attr = TemplateAttribute(documentAttribute = docAttr)
        assertEquals(AttributeLocation.ITEM, attr.location)
    }
}

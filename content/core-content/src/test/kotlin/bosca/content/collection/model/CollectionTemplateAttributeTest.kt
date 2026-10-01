package bosca.content.collection.model

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionTemplateAttributeTest {

    @Test
    fun `stores all properties`() {
        val metadataId = Uuid.random()
        val config = JsonPrimitive("cfg")
        val tools = JsonPrimitive("tools")

        val attr = CollectionTemplateAttribute(
            metadataId = metadataId,
            version = 2,
            key = "title",
            name = "Title",
            description = "The title field",
            supplementaryKey = "supp-key",
            configuration = config,
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT,
            list = true,
            sort = 5,
            location = AttributeLocation.RELATIONSHIP,
            tools = tools
        )

        assertEquals(metadataId, attr.metadataId)
        assertEquals(2, attr.version)
        assertEquals("title", attr.key)
        assertEquals("Title", attr.name)
        assertEquals("The title field", attr.description)
        assertEquals("supp-key", attr.supplementaryKey)
        assertEquals(config, attr.configuration)
        assertEquals(AttributeType.STRING, attr.type)
        assertEquals(AttributeUiType.INPUT, attr.ui)
        assertEquals(true, attr.list)
        assertEquals(5, attr.sort)
        assertEquals(AttributeLocation.RELATIONSHIP, attr.location)
        assertEquals(tools, attr.tools)
    }

    @Test
    fun `default values`() {
        val attr = CollectionTemplateAttribute(
            metadataId = Uuid.random(),
            version = 1,
            key = "k",
            name = "n",
            description = "d",
            type = AttributeType.INT,
            ui = AttributeUiType.TEXTAREA
        )

        assertNull(attr.supplementaryKey)
        assertNull(attr.configuration)
        assertFalse(attr.list)
        assertEquals(0, attr.sort)
        assertEquals(AttributeLocation.ITEM, attr.location)
        assertNull(attr.tools)
    }

    @Test
    fun `equality based on all fields`() {
        val id = Uuid.random()
        val a = CollectionTemplateAttribute(
            metadataId = id, version = 1, key = "k", name = "n",
            description = "d", type = AttributeType.STRING, ui = AttributeUiType.INPUT
        )
        val b = CollectionTemplateAttribute(
            metadataId = id, version = 1, key = "k", name = "n",
            description = "d", type = AttributeType.STRING, ui = AttributeUiType.INPUT
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val id = Uuid.random()
        val a = CollectionTemplateAttribute(
            metadataId = id, version = 1, key = "k", name = "n",
            description = "d", type = AttributeType.STRING, ui = AttributeUiType.INPUT
        )
        val b = CollectionTemplateAttribute(
            metadataId = id, version = 1, key = "k", name = "n",
            description = "d", type = AttributeType.INT, ui = AttributeUiType.INPUT
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = CollectionTemplateAttribute(
            metadataId = Uuid.random(), version = 1, key = "k", name = "n",
            description = "d", type = AttributeType.STRING, ui = AttributeUiType.INPUT
        )
        val copied = original.copy(name = "Updated", sort = 10, list = true)
        assertEquals("Updated", copied.name)
        assertEquals(10, copied.sort)
        assertEquals(true, copied.list)
        assertEquals(original.metadataId, copied.metadataId)
        assertEquals(original.key, copied.key)
    }
}

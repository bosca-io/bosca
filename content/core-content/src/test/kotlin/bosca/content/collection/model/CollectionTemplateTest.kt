package bosca.content.collection.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionTemplateTest {

    @Test
    fun `stores all properties`() {
        val metadataId = Uuid.random()
        val config = JsonPrimitive("cfg")
        val defaults = buildJsonObject { put("a", JsonPrimitive(1)) }
        val filters = buildJsonObject { put("f", JsonPrimitive(true)) }
        val ordering = JsonPrimitive("asc")

        val template = CollectionTemplate(
            metadataId = metadataId,
            version = 3,
            configuration = config,
            defaultAttributes = defaults,
            filters = filters,
            ordering = ordering
        )

        assertEquals(metadataId, template.metadataId)
        assertEquals(3, template.version)
        assertEquals(config, template.configuration)
        assertEquals(defaults, template.defaultAttributes)
        assertEquals(filters, template.filters)
        assertEquals(ordering, template.ordering)
    }

    @Test
    fun `filters defaults to empty JsonObject`() {
        val template = CollectionTemplate(
            metadataId = Uuid.random(),
            version = 1,
            configuration = null,
            defaultAttributes = null,
            ordering = null
        )

        assertEquals(JsonObject(emptyMap()), template.filters)
        assertNull(template.configuration)
        assertNull(template.defaultAttributes)
        assertNull(template.ordering)
    }

    @Test
    fun `equality based on all fields`() {
        val id = Uuid.random()
        val a = CollectionTemplate(metadataId = id, version = 1, configuration = null, defaultAttributes = null, ordering = null)
        val b = CollectionTemplate(metadataId = id, version = 1, configuration = null, defaultAttributes = null, ordering = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val id = Uuid.random()
        val a = CollectionTemplate(metadataId = id, version = 1, configuration = null, defaultAttributes = null, ordering = null)
        val b = CollectionTemplate(metadataId = id, version = 2, configuration = null, defaultAttributes = null, ordering = null)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = CollectionTemplate(
            metadataId = Uuid.random(),
            version = 1,
            configuration = null,
            defaultAttributes = null,
            ordering = null
        )
        val copied = original.copy(version = 5, configuration = JsonPrimitive("new"))
        assertEquals(5, copied.version)
        assertEquals(JsonPrimitive("new"), copied.configuration)
        assertEquals(original.metadataId, copied.metadataId)
    }
}

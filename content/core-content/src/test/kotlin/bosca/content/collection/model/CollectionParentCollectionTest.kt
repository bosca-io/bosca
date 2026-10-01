package bosca.content.collection.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionParentCollectionTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `stores id field`() {
        val parent = CollectionParentCollection(id = testId)
        assertEquals(testId, parent.id)
    }

    @Test
    fun `attributes defaults to null`() {
        val parent = CollectionParentCollection(id = testId)
        assertNull(parent.attributes)
    }

    @Test
    fun `stores attributes when provided`() {
        val attrs = JsonObject(mapOf("role" to JsonPrimitive("primary")))
        val parent = CollectionParentCollection(id = testId, attributes = attrs)
        assertEquals(attrs, parent.attributes)
    }
}

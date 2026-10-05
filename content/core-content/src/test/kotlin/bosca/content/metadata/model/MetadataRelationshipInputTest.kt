package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MetadataRelationshipInputTest {

    private val id1 = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val id2 = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val input = MetadataRelationshipInput(
            id1 = id1,
            id2 = id2,
            relationship = "parent"
        )
        assertEquals(id1, input.id1)
        assertEquals(id2, input.id2)
        assertEquals("parent", input.relationship)
    }

    @Test
    fun attributesDefaultToNull() {
        val input = MetadataRelationshipInput(id1 = id1, id2 = id2, relationship = "ref")
        assertNull(input.attributes)
    }

    @Test
    fun dataClassEquality() {
        val a = MetadataRelationshipInput(id1 = id1, id2 = id2, relationship = "child")
        val b = MetadataRelationshipInput(id1 = id1, id2 = id2, relationship = "child")
        assertEquals(a, b)
    }
}

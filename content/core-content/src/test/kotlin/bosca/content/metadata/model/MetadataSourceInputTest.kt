package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MetadataSourceInputTest {

    @Test
    fun fieldsArePreserved() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val input = MetadataSourceInput(
            id = id,
            identifier = "ext-123",
            sourceUrl = "https://example.com/source"
        )
        assertEquals(id, input.id)
        assertEquals("ext-123", input.identifier)
        assertEquals("https://example.com/source", input.sourceUrl)
    }

    @Test
    fun allFieldsDefaultToNull() {
        val input = MetadataSourceInput()
        assertNull(input.id)
        assertNull(input.identifier)
        assertNull(input.sourceUrl)
    }

    @Test
    fun dataClassEquality() {
        val a = MetadataSourceInput(identifier = "abc")
        val b = MetadataSourceInput(identifier = "abc")
        assertEquals(a, b)
    }
}

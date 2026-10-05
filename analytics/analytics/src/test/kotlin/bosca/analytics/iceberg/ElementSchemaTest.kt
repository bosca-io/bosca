package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElementSchemaTest {

    @Test
    fun `Element schema has exactly four fields`() {
        assertEquals(4, Element.fields().size)
    }

    @Test
    fun `id field is required string at id 171`() {
        val field = Element.field(171)
        assertNotNull(field)
        assertEquals("id", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `type field is required string at id 172`() {
        val field = Element.field(172)
        assertNotNull(field)
        assertEquals("type", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `content field is required list at id 173`() {
        val field = Element.field(173)
        assertNotNull(field)
        assertEquals("content", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.ListType)
    }

    @Test
    fun `content list element type matches Content struct`() {
        val field = Element.field(173)
        assertNotNull(field)
        val listType = field.type() as Types.ListType
        assertEquals(Content, listType.elementType())
    }

    @Test
    fun `extras field is required string at id 174`() {
        val field = Element.field(174)
        assertNotNull(field)
        assertEquals("extras", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `all Element fields are required`() {
        for (field in Element.fields()) {
            assertTrue(field.isRequired, "Field ${field.name()} should be required")
        }
    }

    @Test
    fun `Element is a StructType`() {
        assertTrue(Element is Types.StructType)
    }
}

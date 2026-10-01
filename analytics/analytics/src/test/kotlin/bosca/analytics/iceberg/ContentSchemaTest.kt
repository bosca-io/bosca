package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContentSchemaTest {

    @Test
    fun `Content schema has exactly four fields`() {
        assertEquals(4, Content.fields().size)
    }

    @Test
    fun `id field is required string at id 1731`() {
        val field = Content.field(1731)
        assertNotNull(field)
        assertEquals("id", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `type field is required string at id 1732`() {
        val field = Content.field(1732)
        assertNotNull(field)
        assertEquals("type", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `index field is optional long at id 1733`() {
        val field = Content.field(1733)
        assertNotNull(field)
        assertEquals("index", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.LongType)
    }

    @Test
    fun `percent field is optional double at id 1734`() {
        val field = Content.field(1734)
        assertNotNull(field)
        assertEquals("percent", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.DoubleType)
    }

    @Test
    fun `Content is a StructType`() {
        assertTrue(Content is Types.StructType)
    }
}

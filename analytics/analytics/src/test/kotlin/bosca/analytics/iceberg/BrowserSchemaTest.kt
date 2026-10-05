package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserSchemaTest {

    @Test
    fun `Browser schema has exactly one field`() {
        assertEquals(1, Browser.fields().size)
    }

    @Test
    fun `agent field is required string at id 1031`() {
        val field = Browser.field(1031)
        assertNotNull(field)
        assertEquals("agent", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `Browser is a StructType`() {
        assertTrue(Browser is Types.StructType)
    }
}

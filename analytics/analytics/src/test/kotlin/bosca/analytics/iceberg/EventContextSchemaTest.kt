package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventContextSchemaTest {

    @Test
    fun `Context schema has exactly seven fields`() {
        assertEquals(7, Context.fields().size)
    }

    @Test
    fun `app_id field is required string at id 101`() {
        val field = Context.field(101)
        assertNotNull(field)
        assertEquals("app_id", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `app_version field is required string at id 102`() {
        val field = Context.field(102)
        assertNotNull(field)
        assertEquals("app_version", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `browser field is optional struct at id 103`() {
        val field = Context.field(103)
        assertNotNull(field)
        assertEquals("browser", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StructType)
    }

    @Test
    fun `browser struct matches Browser schema`() {
        val field = Context.field(103)
        assertNotNull(field)
        assertEquals(Browser, field.type())
    }

    @Test
    fun `device field is required struct at id 105`() {
        val field = Context.field(105)
        assertNotNull(field)
        assertEquals("device", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StructType)
    }

    @Test
    fun `device struct matches Device schema`() {
        val field = Context.field(105)
        assertNotNull(field)
        assertEquals(Device, field.type())
    }

    @Test
    fun `geo field is optional struct at id 106`() {
        val field = Context.field(106)
        assertNotNull(field)
        assertEquals("geo", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StructType)
    }

    @Test
    fun `geo struct matches Geo schema`() {
        val field = Context.field(106)
        assertNotNull(field)
        assertEquals(Geo, field.type())
    }

    @Test
    fun `session_id field is required string at id 107`() {
        val field = Context.field(107)
        assertNotNull(field)
        assertEquals("session_id", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `user_id field is optional string at id 108`() {
        val field = Context.field(108)
        assertNotNull(field)
        assertEquals("user_id", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `Context is a StructType`() {
        assertTrue(Context is Types.StructType)
    }
}

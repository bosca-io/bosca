package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ErrorSchemaTest {

    @Test
    fun `Error schema has exactly seven fields`() {
        assertEquals(7, Error.fields().size)
    }

    @Test
    fun `message field is required string at id 181`() {
        val field = Error.field(181)
        assertNotNull(field)
        assertEquals("message", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `type field is optional string at id 182`() {
        val field = Error.field(182)
        assertNotNull(field)
        assertEquals("type", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `stack_trace field is optional string at id 183`() {
        val field = Error.field(183)
        assertNotNull(field)
        assertEquals("stack_trace", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `fatal field is required boolean at id 184`() {
        val field = Error.field(184)
        assertNotNull(field)
        assertEquals("fatal", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.BooleanType)
    }

    @Test
    fun `code field is optional string at id 185`() {
        val field = Error.field(185)
        assertNotNull(field)
        assertEquals("code", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `fingerprint field is optional string at id 186`() {
        val field = Error.field(186)
        assertNotNull(field)
        assertEquals("fingerprint", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `context_json field is optional string at id 187`() {
        val field = Error.field(187)
        assertNotNull(field)
        assertEquals("context_json", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `Error is a StructType`() {
        assertTrue(Error is Types.StructType)
    }
}

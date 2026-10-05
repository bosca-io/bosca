package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceSchemaTest {

    @Test
    fun `Device schema has exactly nine fields`() {
        assertEquals(9, Device.fields().size)
    }

    @Test
    fun `installation_id field is required string at id 1051`() {
        val field = Device.field(1051)
        assertNotNull(field)
        assertEquals("installation_id", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `manufacturer field is required string at id 1052`() {
        val field = Device.field(1052)
        assertNotNull(field)
        assertEquals("manufacturer", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `model field is required string at id 1053`() {
        val field = Device.field(1053)
        assertNotNull(field)
        assertEquals("model", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `platform field is required string at id 1054`() {
        val field = Device.field(1054)
        assertNotNull(field)
        assertEquals("platform", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `primary_locale field is required string at id 1055`() {
        val field = Device.field(1055)
        assertNotNull(field)
        assertEquals("primary_locale", field.name())
        assertTrue(field.isRequired)
    }

    @Test
    fun `system_name field is required string at id 1056`() {
        val field = Device.field(1056)
        assertNotNull(field)
        assertEquals("system_name", field.name())
        assertTrue(field.isRequired)
    }

    @Test
    fun `timezone field is required string at id 1057`() {
        val field = Device.field(1057)
        assertNotNull(field)
        assertEquals("timezone", field.name())
        assertTrue(field.isRequired)
    }

    @Test
    fun `type field is required string at id 1058`() {
        val field = Device.field(1058)
        assertNotNull(field)
        assertEquals("type", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `version field is required string at id 1059`() {
        val field = Device.field(1059)
        assertNotNull(field)
        assertEquals("version", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `all Device fields are required`() {
        for (field in Device.fields()) {
            assertTrue(field.isRequired, "Field ${field.name()} should be required")
        }
    }

    @Test
    fun `Device is a StructType`() {
        assertTrue(Device is Types.StructType)
    }
}

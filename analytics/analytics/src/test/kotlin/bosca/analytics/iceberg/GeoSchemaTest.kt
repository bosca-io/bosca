package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeoSchemaTest {

    @Test
    fun `Geo schema has exactly nine fields`() {
        assertEquals(9, Geo.fields().size)
    }

    @Test
    fun `city field is optional string at id 1061`() {
        val field = Geo.field(1061)
        assertNotNull(field)
        assertEquals("city", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `country field is optional string at id 1062`() {
        val field = Geo.field(1062)
        assertNotNull(field)
        assertEquals("country", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `continent field is optional string at id 1063`() {
        val field = Geo.field(1063)
        assertNotNull(field)
        assertEquals("continent", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `longitude field is optional double at id 1064`() {
        val field = Geo.field(1064)
        assertNotNull(field)
        assertEquals("longitude", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.DoubleType)
    }

    @Test
    fun `latitude field is optional double at id 1065`() {
        val field = Geo.field(1065)
        assertNotNull(field)
        assertEquals("latitude", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.DoubleType)
    }

    @Test
    fun `region field is optional string at id 1066`() {
        val field = Geo.field(1066)
        assertNotNull(field)
        assertEquals("region", field.name())
        assertTrue(field.isOptional)
    }

    @Test
    fun `region_code field is optional string at id 1067`() {
        val field = Geo.field(1067)
        assertNotNull(field)
        assertEquals("region_code", field.name())
        assertTrue(field.isOptional)
    }

    @Test
    fun `postal_code field is optional string at id 1068`() {
        val field = Geo.field(1068)
        assertNotNull(field)
        assertEquals("postal_code", field.name())
        assertTrue(field.isOptional)
    }

    @Test
    fun `timezone field is optional string at id 1069`() {
        val field = Geo.field(1069)
        assertNotNull(field)
        assertEquals("timezone", field.name())
        assertTrue(field.isOptional)
    }

    @Test
    fun `all Geo fields are optional`() {
        for (field in Geo.fields()) {
            assertTrue(field.isOptional, "Field ${field.name()} should be optional")
        }
    }

    @Test
    fun `Geo is a StructType`() {
        assertTrue(Geo is Types.StructType)
    }
}

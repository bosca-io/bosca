package bosca.analytics.iceberg

import org.apache.iceberg.types.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventSchemaTest {

    @Test
    fun `EventSchema has thirteen top-level fields after page was added`() {
        // 12 original fields + page (added in Phase 3 of the conversion-goals
        // overhaul). If this number changes, audit Page.kt and EventSchema.kt
        // for accidental ID collisions before bumping the count.
        assertEquals(13, EventSchema.columns().size)
    }

    @Test
    fun `page field is optional struct at id 2000`() {
        val field = EventSchema.findField(2000)
        assertNotNull(field)
        assertEquals("page", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StructType)
    }

    @Test
    fun `page struct exposes path url and title in the 2000 ID block`() {
        val pageStruct = EventSchema.findField(2000)?.type() as? Types.StructType
        assertNotNull(pageStruct)
        // Field IDs are part of the wire contract — if anyone bumps these the
        // test fails before the bump can land in production.
        val path = pageStruct.field(2001)
        assertNotNull(path)
        assertEquals("path", path.name())
        assertTrue(path.isOptional)
        val url = pageStruct.field(2002)
        assertNotNull(url)
        assertEquals("url", url.name())
        val title = pageStruct.field(2003)
        assertNotNull(title)
        assertEquals("title", title.name())
    }

    @Test
    fun `the 2000 ID block is reserved for the page struct only`() {
        // A future commit could accidentally hand out a column ID in
        // the 2000-2099 range to something other than the page struct
        // (or to a page sub-field outside the 2000-2003 range). Either
        // would be a wire-incompatible change because field IDs are
        // part of the persisted Iceberg contract. This test whitelists
        // exactly the four IDs the page struct uses today and asserts
        // nothing else lands in the 2000 block.
        //
        // The whitelist is per-block (top-level field 2000 + nested page
        // sub-fields 2001..2003). Adding a new page sub-field is fine —
        // bump RESERVED_PAGE_BLOCK_IDS in the same commit and the test
        // documents the new contract.
        val RESERVED_PAGE_BLOCK_IDS = setOf(2000, 2001, 2002, 2003)
        val PAGE_BLOCK_RANGE = 2000..2099

        // Top-level columns: only field 2000 is allowed in the block.
        for (column in EventSchema.columns()) {
            val id = column.fieldId()
            if (id in PAGE_BLOCK_RANGE) {
                assertEquals(
                    2000, id,
                    "Top-level field '${column.name()}' has id $id which collides with the page-struct block."
                )
            }
        }

        // Nested page sub-fields: must be exactly the whitelisted set.
        val pageStruct = EventSchema.findField(2000)?.type() as? Types.StructType
        assertNotNull(pageStruct)
        val pageFieldIds = pageStruct.fields().map { it.fieldId() }.toSet()
        for (id in pageFieldIds) {
            assertTrue(
                id in RESERVED_PAGE_BLOCK_IDS,
                "Page sub-field has id $id which is not in the whitelist $RESERVED_PAGE_BLOCK_IDS."
            )
        }
    }

    @Test
    fun `error field is optional struct at id 12`() {
        val field = EventSchema.findField(12)
        assertNotNull(field)
        assertEquals("error", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StructType)
    }

    @Test
    fun `error struct matches Error schema`() {
        val field = EventSchema.findField(12)
        assertNotNull(field)
        val errorStruct = field.type() as Types.StructType
        assertEquals(Error, errorStruct)
    }

    @Test
    fun `id field is required UUID at id 1`() {
        val field = EventSchema.findField(1)
        assertNotNull(field)
        assertEquals("id", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.UUIDType)
    }

    @Test
    fun `type field is required string at id 3`() {
        val field = EventSchema.findField(3)
        assertNotNull(field)
        assertEquals("type", field.name())
        assertTrue(field.isRequired)
        assertTrue(field.type() is Types.StringType)
    }

    @Test
    fun `client_id field is optional string at id 2`() {
        val field = EventSchema.findField(2)
        assertNotNull(field)
        assertEquals("client_id", field.name())
        assertTrue(field.isOptional)
    }

    @Test
    fun `context field is optional struct at id 10`() {
        val field = EventSchema.findField(10)
        assertNotNull(field)
        assertEquals("context", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StructType)
    }

    @Test
    fun `element field is optional struct at id 11`() {
        val field = EventSchema.findField(11)
        assertNotNull(field)
        assertEquals("element", field.name())
        assertTrue(field.isOptional)
        assertTrue(field.type() is Types.StructType)
    }
}

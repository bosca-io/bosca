package bosca.documents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TableColumnWidthsTest {
    private val json = Json { serializersModule = DocumentSerializers }

    @Test
    fun `resized cells and headers survive the canonical document wire format`() {
        val wire = """{"document":{"type":"doc","content":[{"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","attrs":{"colspan":2,"rowspan":1,"colwidth":[140,220]},"content":[{"type":"paragraph"}]},{"type":"tableCell","attrs":{"colspan":1,"rowspan":1,"colwidth":[180]},"content":[{"type":"paragraph"}]}]}]}]}}"""
        val content = json.decodeFromString(Content.serializer(), wire)
        val row = content.document.content.single().content.single()
        val header = assertIs<TableHeaderNode>(row.content[0])
        val cell = assertIs<TableCellNode>(row.content[1])
        assertEquals(listOf(140, 220), header.attributes.colwidth)
        assertEquals(listOf(180), cell.attributes.colwidth)
        assertEquals(header.attributes.colwidth, header.attributes.withClasses("heading").colwidth)
        assertEquals(cell.attributes.colwidth, cell.attributes.withClasses("body").colwidth)
        val encoded = json.parseToJsonElement(json.encodeToString(Content.serializer(), content))
            .jsonObject.getValue("document").jsonObject.getValue("content").jsonArray[0]
            .jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("content").jsonArray
        assertEquals("[140,220]", encoded[0].jsonObject.getValue("attrs").jsonObject.getValue("colwidth").toString())
        assertEquals("[180]", encoded[1].jsonObject.getValue("attrs").jsonObject.getValue("colwidth").toString())
    }

    @Test
    fun `unresized and explicitly null widths remain unset`() {
        for (attributes in listOf("{}", "{\"colwidth\":null}")) {
            assertNull(json.decodeFromString(TableCellNodeAttributes.serializer(), attributes).colwidth)
            assertNull(json.decodeFromString(TableHeaderNodeAttributes.serializer(), attributes).colwidth)
        }
    }
}

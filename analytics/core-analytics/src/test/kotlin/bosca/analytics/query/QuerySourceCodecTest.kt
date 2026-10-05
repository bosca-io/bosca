package bosca.analytics.query

import bosca.analytics.model.QueryParameterType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuerySourceCodecTest {

    @Test
    fun `parse returns null parameters when no block is present`() {
        val sql = "SELECT 1"
        val result = QuerySourceCodec.parse(sql)
        assertEquals(sql, result.cleanSql)
        assertNull(result.parameters)
    }

    @Test
    fun `parse returns null parameters for empty input`() {
        val result = QuerySourceCodec.parse("")
        assertEquals("", result.cleanSql)
        assertNull(result.parameters)
    }

    @Test
    fun `parse returns null parameters for whitespace-only input`() {
        val result = QuerySourceCodec.parse("   \n  ")
        assertEquals("   \n  ", result.cleanSql)
        assertNull(result.parameters)
    }

    @Test
    fun `parse ignores a leading regular comment that is not bosca-query`() {
        val sql = "/* a note about the query */\nSELECT 1"
        val result = QuerySourceCodec.parse(sql)
        assertEquals(sql, result.cleanSql)
        assertNull(result.parameters)
    }

    @Test
    fun `parse ignores bosca-query marker that is not at the start of the file`() {
        val sql = "SELECT '/* @bosca-query in a string literal */'"
        val result = QuerySourceCodec.parse(sql)
        assertEquals(sql, result.cleanSql)
        assertNull(result.parameters)
    }

    @Test
    fun `parse extracts a single parameter declaration`() {
        val sql = """
            /* @bosca-query
            { "parameters": [
              { "parameter": "startDate", "name": "Start", "type": "DATE", "required": true }
            ] }
            */
            SELECT * FROM events WHERE created >= :startDate
        """.trimIndent()

        val result = QuerySourceCodec.parse(sql)

        assertEquals("SELECT * FROM events WHERE created >= :startDate", result.cleanSql)
        val params = assertNotNull(result.parameters)
        assertEquals(1, params.size)
        val p = params[0]
        assertEquals("startDate", p.parameter)
        assertEquals("Start", p.name)
        assertEquals("", p.description)
        assertEquals(QueryParameterType.DATE, p.type)
        assertEquals(QueryParameterType.NONE, p.arrayType)
        assertNull(p.defaultValue)
        assertEquals(true, p.required)
        assertEquals(0, p.sort)
    }

    @Test
    fun `parse honors explicit sort when provided`() {
        val sql = """
            /* @bosca-query
            { "parameters": [
              { "parameter": "a", "name": "A", "type": "STRING", "sort": 7 },
              { "parameter": "b", "name": "B", "type": "STRING" }
            ] }
            */
            SELECT 1
        """.trimIndent()

        val params = QuerySourceCodec.parse(sql).parameters!!

        assertEquals(7, params[0].sort)
        assertEquals(1, params[1].sort)
    }

    @Test
    fun `parse accepts an empty parameters array as a block with zero params`() {
        val sql = """
            /* @bosca-query
            { "parameters": [] }
            */
            SELECT 1
        """.trimIndent()

        val result = QuerySourceCodec.parse(sql)

        assertEquals("SELECT 1", result.cleanSql)
        assertEquals(emptyList(), result.parameters)
    }

    @Test
    fun `parse accepts complex defaultValue as JsonElement`() {
        val sql = """
            /* @bosca-query
            { "parameters": [
              { "parameter": "tags", "name": "Tags", "type": "ARRAY", "arrayType": "STRING", "defaultValue": ["a", "b"] }
            ] }
            */
            SELECT 1
        """.trimIndent()

        val p = QuerySourceCodec.parse(sql).parameters!!.single()

        assertEquals(QueryParameterType.ARRAY, p.type)
        assertEquals(QueryParameterType.STRING, p.arrayType)
        assertEquals("""["a","b"]""", p.defaultValue.toString())
    }

    @Test
    fun `parse strips trailing CRLF after the closing comment`() {
        val sql = "/* @bosca-query\n{\"parameters\":[]}\n*/\r\nSELECT 1"
        val result = QuerySourceCodec.parse(sql)
        assertEquals("SELECT 1", result.cleanSql)
    }

    @Test
    fun `parse preserves SQL when no newline follows the closing comment`() {
        val sql = "/* @bosca-query\n{\"parameters\":[]}\n*/SELECT 1"
        val result = QuerySourceCodec.parse(sql)
        assertEquals("SELECT 1", result.cleanSql)
    }

    @Test
    fun `parse throws when block is unterminated`() {
        val sql = "/* @bosca-query\n{ \"parameters\": [] }\nSELECT 1"
        assertFailsWith<QuerySourceParseException> { QuerySourceCodec.parse(sql) }
    }

    @Test
    fun `parse throws when JSON is malformed`() {
        val sql = "/* @bosca-query\n{ not json }\n*/\nSELECT 1"
        val ex = assertFailsWith<QuerySourceParseException> { QuerySourceCodec.parse(sql) }
        assertTrue(ex.message!!.contains("Invalid JSON"))
    }

    @Test
    fun `parse throws when parameter type is unknown`() {
        val sql = """
            /* @bosca-query
            { "parameters": [ { "parameter": "x", "name": "X", "type": "ENUM" } ] }
            */
            SELECT 1
        """.trimIndent()

        assertFailsWith<QuerySourceParseException> { QuerySourceCodec.parse(sql) }
    }

    @Test
    fun `render returns sql unchanged when parameters list is empty`() {
        val sql = "SELECT 1"
        assertEquals(sql, QuerySourceCodec.render(sql, emptyList()))
    }

    @Test
    fun `render prepends a block and preserves trailing SQL`() {
        val params = listOf(
            QueryParameterDeclaration(
                parameter = "startDate",
                name = "Start",
                type = QueryParameterType.DATE,
                required = true,
            )
        )

        val out = QuerySourceCodec.render("SELECT 1", params)

        assertTrue(out.startsWith("/* @bosca-query\n"), "missing opening marker: $out")
        assertTrue(out.endsWith("\nSELECT 1"), "trailing SQL not preserved: $out")
        assertTrue(out.contains("\"startDate\""), "parameter name not encoded: $out")
    }

    @Test
    fun `round trip render then parse yields the same clean sql and parameters`() {
        val sql = "SELECT * FROM events WHERE created >= :startDate AND tags && :tags;"
        val params = listOf(
            QueryParameterDeclaration(
                parameter = "startDate",
                name = "Start date",
                description = "Range start",
                type = QueryParameterType.DATE,
                required = true,
                defaultValue = JsonPrimitive("2026-01-01"),
                sort = 0,
            ),
            QueryParameterDeclaration(
                parameter = "tags",
                name = "Tags",
                type = QueryParameterType.ARRAY,
                arrayType = QueryParameterType.STRING,
                defaultValue = buildJsonObject { },
                sort = 1,
            ),
        )

        val rendered = QuerySourceCodec.render(sql, params)
        val parsed = QuerySourceCodec.parse(rendered)

        assertEquals(sql, parsed.cleanSql)
        assertEquals(params, parsed.parameters)
    }

    @Test
    fun `render normalizes sort to index when missing`() {
        val params = listOf(
            QueryParameterDeclaration(parameter = "a", name = "A", type = QueryParameterType.STRING),
            QueryParameterDeclaration(parameter = "b", name = "B", type = QueryParameterType.STRING),
        )

        val parsed = QuerySourceCodec.parse(QuerySourceCodec.render("SELECT 1", params)).parameters!!

        assertEquals(0, parsed[0].sort)
        assertEquals(1, parsed[1].sort)
    }

    @Test
    fun `render is idempotent when its input already contains a block`() {
        val params = listOf(
            QueryParameterDeclaration(parameter = "a", name = "A", type = QueryParameterType.STRING, sort = 0),
        )

        val once = QuerySourceCodec.render("SELECT 1", params)
        val twice = QuerySourceCodec.render(once, params)

        assertEquals(once, twice)
        // And the round-trip still recovers exactly one block.
        val parsed = QuerySourceCodec.parse(twice)
        assertEquals("SELECT 1", parsed.cleanSql)
        assertEquals(params, parsed.parameters)
    }

    @Test
    fun `render strips a stale block when rewriting with different parameters`() {
        val withOld = QuerySourceCodec.render(
            "SELECT 1",
            listOf(QueryParameterDeclaration(parameter = "old", name = "Old", type = QueryParameterType.STRING, sort = 0)),
        )
        val rewritten = QuerySourceCodec.render(
            withOld,
            listOf(QueryParameterDeclaration(parameter = "new", name = "New", type = QueryParameterType.INTEGER, sort = 0)),
        )

        val parsed = QuerySourceCodec.parse(rewritten)
        assertEquals("SELECT 1", parsed.cleanSql)
        assertEquals(1, parsed.parameters?.size)
        assertEquals("new", parsed.parameters?.single()?.parameter)
        assertEquals(QueryParameterType.INTEGER, parsed.parameters?.single()?.type)
    }
}

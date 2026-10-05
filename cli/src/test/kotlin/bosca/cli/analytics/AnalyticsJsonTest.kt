package bosca.cli.analytics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsJsonTest {

    @Test
    fun `execution parameters parse JSON values and preserve plain strings`() {
        val parameters = parseExecutionParameters(
            listOf(
                "limit=25",
                "active=true",
                "filters={\"country\":\"US\"}",
                "label=weekly traffic",
            ),
        )

        assertEquals("limit", parameters[0].parameter)
        assertEquals(25, requireNotNull(parameters[0].value).jsonPrimitive.content.toInt())
        assertEquals(true, requireNotNull(parameters[1].value).jsonPrimitive.content.toBoolean())
        assertEquals(
            "US",
            requireNotNull(parameters[2].value).jsonObject.getValue("country").jsonPrimitive.content,
        )
        assertEquals("weekly traffic", requireNotNull(parameters[3].value).jsonPrimitive.content)
    }

    @Test
    fun `records result is bounded and reports truncation plus cache metadata`() {
        val records = (1..3).map { value -> buildJsonObject { put("value", value) } }

        val result = recordsJson(records, cached = true, refreshedAt = null, maxRecords = 2)

        assertEquals(2, result.getValue("returnedRecords").jsonPrimitive.content.toInt())
        assertTrue(result.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertTrue(result.getValue("cached").jsonPrimitive.content.toBoolean())
        assertEquals(JsonNull, result.getValue("refreshedAt"))
        assertEquals(2, result.getValue("records").jsonArray.size)
    }

    @Test
    fun `table output keeps a stable union of record columns`() {
        val records = listOf(
            buildJsonObject { put("name", "alpha"); put("count", 3) },
            buildJsonObject { put("name", "beta"); put("ratio", 0.5) },
        )

        val lines = tableLines(records)

        assertTrue(lines.first().contains("name"))
        assertTrue(lines.first().contains("count"))
        assertTrue(lines.first().contains("ratio"))
    }
}

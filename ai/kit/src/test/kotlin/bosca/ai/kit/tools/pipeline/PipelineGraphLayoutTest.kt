package bosca.ai.kit.tools.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PipelineGraphLayoutTest {
    private val json = Json

    @Test
    fun `layout is deterministic for a branch and merge graph`() {
        val graph = json.parseToJsonElement(
            """{
              "nodes": [
                {"type":"merge","id":"merge","position":{"x":999,"y":999}},
                {"type":"branch","id":"right"},
                {"type":"input","id":"input"},
                {"type":"branch","id":"left"}
              ],
              "edges": [
                {"id":"e4","source":"right","target":"merge"},
                {"id":"e2","source":"input","target":"right"},
                {"id":"e3","source":"left","target":"merge"},
                {"id":"e1","source":"input","target":"left"}
              ]
            }""",
        )

        val first = PipelineGraphLayout.layout(graph)
        val second = PipelineGraphLayout.layout(graph)

        assertEquals(first, second)
        val positions = ((first as JsonObject).getValue("nodes") as JsonArray).associate { node ->
            val objectNode = node.jsonObject
            objectNode.getValue("id").jsonPrimitive.content to objectNode.getValue("position").jsonObject
        }
        assertEquals("0.0", positions.getValue("input").getValue("x").jsonPrimitive.content)
        assertEquals("320.0", positions.getValue("left").getValue("x").jsonPrimitive.content)
        assertEquals("320.0", positions.getValue("right").getValue("x").jsonPrimitive.content)
        assertEquals("640.0", positions.getValue("merge").getValue("x").jsonPrimitive.content)
        assertNotEquals(
            positions.getValue("left").getValue("y"),
            positions.getValue("right").getValue("y"),
        )
    }
}

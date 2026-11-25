package bosca.pipelines.model

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
    fun `malformed graph envelopes are left for engine validation`() {
        for (value in listOf("null", "{}", "{\"nodes\":[1]}", "{\"nodes\":[{\"id\":\"same\"},{\"id\":\"same\"}]}", "{\"nodes\":[{}]}", "{\"nodes\":[{\"id\":null}]}")) {
            val graph = json.parseToJsonElement(value)
            assertEquals(graph, PipelineGraphLayout.layout(graph))
        }
    }

    @Test
    fun `isolated nodes and invalid edges do not affect the valid graph layout`() {
        val base = json.parseToJsonElement("""{"nodes":[{"id":"a"},{"id":"b"}]}""")
        val edges = json.parseToJsonElement("""[null,{}, {"source":null,"target":"b"}, {"source":"a","target":null}, {"source":"a"}, {"source":"unknown","target":"b"}, {"source":"a","target":"unknown"}]""")
        val withEdges = JsonObject(base.jsonObject + ("edges" to edges))
        val laidOut = PipelineGraphLayout.layout(base).jsonObject
        assertEquals(laidOut["nodes"], PipelineGraphLayout.layout(withEdges).jsonObject["nodes"])
        assertEquals(json.parseToJsonElement("{\"nodes\":[]}"), PipelineGraphLayout.layout(json.parseToJsonElement("{\"nodes\":[]}")))
    }

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

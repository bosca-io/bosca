package bosca.workops.ui

import bosca.design.model.schemaFormNodes
import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans
import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans.Inputs
import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans.Jobs
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseCiInputsTest {
    private fun input(name: String, type: String = "string", default: String? = null, options: List<String> = emptyList()) =
        Inputs(name, type, default, "Description for $name", options, default == null)
    private fun plan(inputs: List<Inputs> = emptyList(), jobs: List<Jobs> = emptyList()) = ReleasePlans("pipeline", "Release", "repository", "source", "RELEASE", null, null, inputs, jobs)

    @Test fun `neutral form keys preserve dotted trigger names and submit false and zero as scalar strings`() {
        val model = WorkOpsReleaseCiInputs(listOf(input("deploy.enabled", "boolean"), input("rollout/percent", "number", "0"), input("notes", default = "")))
        val nodes = schemaFormNodes(model.schema, model.uiSchema, model.defaults)
        assertEquals(listOf("input0", "input1", "input2"), nodes.map { it.property })
        assertEquals(listOf("deploy.enabled", "rollout/percent", "notes"), nodes.map { it.label })
        assertEquals(listOf("checkbox", "number-input", "text-input"), nodes.map { it.control }); assertFalse(nodes.first().checked)
        val payload = model.payload("""{"input0":false,"input1":0,"input2":"","unsubmitted":"ignored"}""")
        assertEquals(JsonPrimitive("false"), payload["deploy.enabled"]); assertEquals(JsonPrimitive("0"), payload["rollout/percent"]); assertEquals(JsonPrimitive(""), payload["notes"])
        assertEquals(3, payload.size)
    }

    @Test fun `required numbers remain unset while choice fields start with the source first option`() {
        val model = WorkOpsReleaseCiInputs(listOf(input("replicas", "number"), input("region", "choice", options = listOf("west", "east"))))
        assertEquals(JsonNull, model.defaults["input0"]); assertEquals(JsonPrimitive("west"), model.defaults["input1"])
        assertFailsWith<IllegalArgumentException> { model.payload("{}") }
        assertEquals(JsonPrimitive("2.5"), model.payload("""{"input0":2.5}""")["replicas"])
    }

    @Test fun `declared defaults and first duplicate declarations match the release source input merge`() {
        val first = plan(listOf(input("flag", "boolean", "true"), input("region", "choice", "east", listOf("west", "east"))))
        val other = plan(listOf(input("flag", "string", "different"), input("message", default = "Saved")))
        val model = WorkOpsReleaseCiInputs.from(listOf(first, other))
        assertEquals(listOf("flag", "region", "message"), model.inputs.map { it.name })
        assertEquals(JsonObject(mapOf("flag" to JsonPrimitive("true"), "region" to JsonPrimitive("east"), "message" to JsonPrimitive("Saved"))), model.payload("{}"))
    }

    @Test fun `missing required text invalid types and structured values fail without inventing scalar conversions`() {
        assertFailsWith<IllegalArgumentException> { WorkOpsReleaseCiInputs(listOf(input("name"))).payload("{}") }
        assertFailsWith<IllegalArgumentException> { WorkOpsReleaseCiInputs(listOf(input("flag", "boolean"))).payload("""{"input0":"yes"}""") }
        assertFailsWith<IllegalArgumentException> { WorkOpsReleaseCiInputs(listOf(input("count", "number"))).payload("""{"input0":"many"}""") }
        assertFailsWith<IllegalArgumentException> { WorkOpsReleaseCiInputs(listOf(input("region", "choice", options = listOf("west")))).payload("""{"input0":"other"}""") }
        assertFailsWith<IllegalArgumentException> { WorkOpsReleaseCiInputs(listOf(input("name"))).payload("""{"input0":{}}""") }
        assertFailsWith<IllegalArgumentException> { WorkOpsReleaseCiInputs().payload("[]") }
        assertEquals(JsonObject(emptyMap()), WorkOpsReleaseCiInputs().payload("{}"))
    }

    @Test fun `read only plan graph uses selected dependency depths and retains missing needs as text`() {
        val build = Jobs("build", null, false, emptyList(), listOf("artifact raw:builds:input:1"), 0, listOf(Jobs.Steps("Build", "run")))
        val gate = Jobs("approve", "staging", true, listOf("build", "not-selected"), emptyList(), 1, emptyList())
        val graph = plan(jobs = listOf(build, gate)).releaseGraph()
        assertEquals(listOf("build", "approve"), graph.nodes.map { it.id }); assertEquals(280.0, graph.nodes.last().position.x)
        assertEquals(listOf("build" to "approve"), graph.edges.map { it.source to it.target })
        assertTrue(graph.nodes.all { it.draggable == false && it.connectable == false })
        assertTrue("not-selected" in graph.nodes.last().data.description.orEmpty()); assertTrue("Checkpoint" in graph.nodes.last().data.description.orEmpty())
        assertTrue(plan().releaseGraph().nodes.isEmpty())
    }
}

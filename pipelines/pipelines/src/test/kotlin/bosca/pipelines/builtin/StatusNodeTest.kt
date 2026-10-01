package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusNodeTest {

    private val context = PipelineContext(AuthenticationContext(null, null), Json)

    @Test
    fun `status passes an inbound value through and emits json null without one`() = runTest {
        val node = StatusNode("built", title = "Built")
        val value = PipelineValue.ofJson(JsonPrimitive("artifact"))

        val passed = node.run(context, NodeInputs(mapOf("in" to value)))
        val empty = node.run(context, NodeInputs(emptyMap()))

        assertTrue(passed is NodeResult.Output)
        assertEquals(JsonPrimitive("artifact"), passed.value?.value)
        assertTrue(empty is NodeResult.Output)
        assertEquals(JsonNull, empty.value?.value)
    }

    @Test
    fun `status computes the same pass-through during a dry run`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true)
        val result = StatusNode("built").run(dry, NodeInputs(emptyMap()))

        assertTrue(result is NodeResult.Output)
        assertEquals(JsonNull, result.value?.value)
    }
}

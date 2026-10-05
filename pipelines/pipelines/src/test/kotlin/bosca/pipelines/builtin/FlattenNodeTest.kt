package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [FlattenNode]: collapses a nested array by one level (`X[][]` → `X[]`), leniently keeping non-array elements. */
class FlattenNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    private suspend fun flatten(value: PipelineValue): JsonArray =
        FlattenNode(id = "f").run(context, NodeInputs(mapOf("in" to value))).result?.value as JsonArray

    @Test
    fun `flattens an array of arrays one level`() = runTest {
        val nested = buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
            add(buildJsonArray { add(JsonPrimitive("c")) })
        }
        val out = flatten(PipelineValue.ofJson(nested))
        assertEquals(listOf("a", "b", "c"), out.map { (it as JsonPrimitive).content })
    }

    @Test
    fun `only flattens one level, leaving deeper nesting intact`() = runTest {
        val nested = buildJsonArray {
            add(buildJsonArray { add(buildJsonArray { add(JsonPrimitive("deep")) }) })
        }
        val out = flatten(PipelineValue.ofJson(nested))
        assertEquals(1, out.size)
        assertEquals(JsonArray::class, out[0]!!::class, "the inner array survives — only one level is removed")
    }

    @Test
    fun `keeps a non-array element as-is`() = runTest {
        val mixed = buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive("a")) })
            add(JsonPrimitive("b"))
        }
        val out = flatten(PipelineValue.ofJson(mixed))
        assertEquals(listOf("a", "b"), out.map { (it as JsonPrimitive).content })
    }

    @Test
    fun `an empty array flattens to an empty array`() = runTest {
        val out = flatten(PipelineValue.ofJson(JsonArray(emptyList())))
        assertEquals(0, out.size)
    }

    @Test
    fun `fails clearly when the input is not an array`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            FlattenNode(id = "f", name = "Flatten").run(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope")))))
        }
        assertEquals(true, (e.message ?: "").contains("requires an array"))
    }

    @Test
    fun `a blank node name falls back to its id in an array error`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            FlattenNode(id = "flatten-id").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope")))),
            )
        }

        assertTrue(failure.message.orEmpty().contains("flatten-id"))
    }
}

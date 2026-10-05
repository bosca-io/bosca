package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToStringNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    private suspend fun render(value: PipelineValue): String? =
        ToStringNode(id = "s").run(context, NodeInputs(mapOf("in" to value))).result?.value as? String

    @Test
    fun `a json string becomes its bare unquoted content`() = runTest {
        assertEquals("hello", render(PipelineValue.ofJson(JsonPrimitive("hello"))))
    }

    @Test
    fun `a number becomes its text`() = runTest {
        assertEquals("42", render(PipelineValue.of(42, Int.serializer())))
    }

    @Test
    fun `a boolean becomes its text`() = runTest {
        assertEquals("true", render(PipelineValue.of(true, Boolean.serializer())))
    }

    @Test
    fun `a uuid becomes its string form`() = runTest {
        val id = UUID.random()
        assertEquals(id.toString(), render(PipelineValue.of(id, UUIDSerializer())))
    }

    @Test
    fun `a structured value becomes its compact JSON text`() = runTest {
        val obj = buildJsonObject { put("a", 1) }
        assertEquals(obj.toString(), render(PipelineValue.ofJson(obj)))
    }

    @Test
    fun `the output is declared a STRING so it can feed a string slot`() = runTest {
        val out = ToStringNode(id = "s").run(context, NodeInputs(mapOf("in" to PipelineValue.of(7, Int.serializer()))))
        // String.serializer's descriptor serial name is "kotlin.String" — a typed string, not unknown.
        assertEquals("kotlin.String", out.result?.typeName)
    }

    @Test
    fun `requires an input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ToStringNode(id = "s", name = "Stringify").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("required input 'in'" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `serializes and round-trips with defaults`() {
        val node = ToStringNode(id = "s", name = "Stringify", description = "d")
        val decoded = Json.decodeFromString(ToStringNode.serializer(), Json.encodeToString(ToStringNode.serializer(), node))
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        val minimal = Json.decodeFromString(ToStringNode.serializer(), """{"id":"only"}""")
        assertEquals("only", minimal.id)
        assertEquals("", minimal.name)
    }

    @Test
    fun `decoding JSON missing the required id throws`() {
        assertFailsWith<SerializationException> { Json.decodeFromString(ToStringNode.serializer(), "{}") }
    }
}

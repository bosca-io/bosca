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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToUuidNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    private suspend fun toUuid(value: PipelineValue): PipelineValue? =
        ToUuidNode(id = "u").run(context, NodeInputs(mapOf("in" to value))).result

    @Test
    fun `parses a json string into a typed uuid`() = runTest {
        val id = UUID.random()
        assertEquals(id, toUuid(PipelineValue.ofJson(JsonPrimitive(id.toString())))?.value)
    }

    @Test
    fun `parses a typed string into a uuid`() = runTest {
        val id = UUID.random()
        assertEquals(id, toUuid(PipelineValue.of(id.toString(), String.serializer()))?.value)
    }

    @Test
    fun `a bare uuid passes straight through`() = runTest {
        val id = UUID.random()
        assertEquals(id, toUuid(PipelineValue.of(id, UUIDSerializer()))?.value)
    }

    @Test
    fun `trims surrounding whitespace before parsing`() = runTest {
        val id = UUID.random()
        assertEquals(id, toUuid(PipelineValue.of("  $id  ", String.serializer()))?.value)
    }

    @Test
    fun `the output is declared a UUID so it can feed a uuid slot`() = runTest {
        val out = ToUuidNode(id = "u").run(
            context,
            NodeInputs(mapOf("in" to PipelineValue.of(UUID.random().toString(), String.serializer()))),
        )
        // UUIDSerializer's descriptor serial name is "UUID" — a typed id, not an unknown value.
        assertEquals("UUID", out.result?.typeName)
    }

    @Test
    fun `invalid text fails with a clear message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ToUuidNode(id = "u", name = "Ident").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.of("not-a-uuid", String.serializer()))),
            )
        }
        assertTrue("Ident" in (e.message ?: "") && "not a valid UUID" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `a non-string input fails rather than producing a bad id`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ToUuidNode(id = "u").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("a", 1) }))),
            )
        }
        assertTrue("not a string" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `json null and invalid text use the node id in their failures`() = runTest {
        val nullFailure = assertFailsWith<IllegalStateException> {
            ToUuidNode(id = "uuid-node").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonNull))),
            )
        }
        assertTrue("uuid-node" in nullFailure.message.orEmpty())

        val invalidFailure = assertFailsWith<IllegalStateException> {
            ToUuidNode(id = "uuid-node").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.of("invalid", String.serializer()))),
            )
        }
        assertTrue("uuid-node" in invalidFailure.message.orEmpty())
    }

    @Test
    fun `requires an input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ToUuidNode(id = "u", name = "Ident").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("required input 'in'" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `serializes and round-trips with defaults`() {
        val node = ToUuidNode(id = "u", name = "Identify", description = "d")
        val decoded = Json.decodeFromString(ToUuidNode.serializer(), Json.encodeToString(ToUuidNode.serializer(), node))
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        val minimal = Json.decodeFromString(ToUuidNode.serializer(), """{"id":"only"}""")
        assertEquals("only", minimal.id)
        assertEquals("", minimal.name)
    }

    @Test
    fun `decoding JSON missing the required id throws`() {
        assertFailsWith<SerializationException> { Json.decodeFromString(ToUuidNode.serializer(), "{}") }
    }
}

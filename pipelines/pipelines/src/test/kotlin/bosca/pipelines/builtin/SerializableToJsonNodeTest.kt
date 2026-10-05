package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SerializableToJsonNodeTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    private val typedValue = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
    private fun typedInputs() = NodeInputs(mapOf("in" to typedValue))

    // --- execute: error arm (null/empty input) ---

    @Test
    fun `execute with no inputs raises an error naming the node id`() = runTest {
        val node = SerializableToJsonNode(id = "toJson")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue(
            "toJson" in (failure.message ?: ""),
            "the error should name the node id, got: ${failure.message}",
        )
        assertTrue(
            "requires an input" in (failure.message ?: ""),
            "the error should explain the missing input, got: ${failure.message}",
        )
    }

    // --- execute: typed value -> JSON (the encode arm of toJson) ---

    @Test
    fun `execute converts a typed value to its JSON form`() = runTest {
        val node = SerializableToJsonNode(id = "toJson")
        val result = node.executeForTestValue(context, typedInputs())

        // value is now a JsonElement (the encoded form), not the original Person.
        val element = result.value
        assertTrue(element is JsonElement, "the output value should be a JsonElement")
        val obj = element as JsonObject
        assertEquals(JsonPrimitive("Ada"), obj["name"])
        assertEquals(JsonPrimitive("ada@x.io"), obj["email"])
    }

    @Test
    fun `converting a typed value retains the origin serializer for reversal`() = runTest {
        val node = SerializableToJsonNode(id = "toJson")
        val result = node.executeForTestValue(context, typedInputs())

        // toJson retains the original serializer as originSerializer, so the JSON form keeps the
        // typed origin's serial name and can be decoded back.
        assertEquals(Person.serializer().descriptor.serialName, result.typeName)
        val back = result.decodeOrigin(json)
        assertEquals(Person("Ada", "ada@x.io"), back?.value)
    }

    // --- execute: already-JSON value passthrough (the value is JsonElement arm of toJson) ---

    @Test
    fun `execute passes an already-JSON value through unchanged`() = runTest {
        val element: JsonElement = buildJsonObject { put("k", "v") }
        val jsonValue = PipelineValue.ofJson(element)
        val node = SerializableToJsonNode(id = "toJson")

        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to jsonValue)))

        // toJson returns `this` for an already-JSON value: same carrier instance, same element.
        assertSame(jsonValue, result, "an already-JSON value must pass through as the same carrier")
        assertSame(element, result.value)
        // No typed origin on plain JSON.
        assertNull(result.typeName)
    }

    // --- run: default wraps execute as NodeResult.Output ---

    @Test
    fun `run wraps the converted value as a NodeResult Output`() = runTest {
        val node = SerializableToJsonNode(id = "toJson")
        val result = node.run(context, typedInputs())
        assertTrue(result is NodeResult.Output, "the default run should emit an Output")
        assertTrue((result as NodeResult.Output).value?.value is JsonElement)
    }

    @Test
    fun `run propagates the missing-input error`() = runTest {
        val node = SerializableToJsonNode(id = "toJson")
        assertFailsWith<IllegalStateException> {
            node.run(context, NodeInputs(emptyMap()))
        }
    }

    // --- node identity / defaults / serialization ---

    @Test
    fun `default constructor params default to empty name and description and origin position`() {
        val node = SerializableToJsonNode(id = "toJson")
        assertEquals("toJson", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `explicit constructor params are retained`() {
        val node = SerializableToJsonNode(
            id = "n1",
            name = "Encode",
            description = "to json",
            position = NodePosition(3.0, 4.0),
        )
        assertEquals("n1", node.id)
        assertEquals("Encode", node.name)
        assertEquals("to json", node.description)
        assertEquals(NodePosition(3.0, 4.0), node.position)
    }

    @Test
    fun `node serializes to and from JSON via its explicit serializer`() {
        val node = SerializableToJsonNode(
            id = "n1",
            name = "Encode",
            description = "to json",
            position = NodePosition(3.0, 4.0),
        )
        val encoded = json.encodeToString(SerializableToJsonNode.serializer(), node)
        val decoded = json.decodeFromString(SerializableToJsonNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node decodes from a minimal JSON exercising default-value arms`() {
        // Only id is required; name, description and position fall back to their defaults.
        val decoded = json.decodeFromString(
            SerializableToJsonNode.serializer(),
            """{"id":"only-id"}""",
        )
        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() {
        // id is the only no-default field; an empty object omits it, driving the generated
        // deserializer's `(seen & required) != required -> throwMissingFieldException` arm — the arm a
        // valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            json.decodeFromString(SerializableToJsonNode.serializer(), "{}")
        }
    }
}

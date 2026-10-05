package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonToSerializableNodeTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    /** A value that has been through a `Serializable → JSON` step, so it carries an origin serializer. */
    private val jsonWithOrigin: PipelineValue
        get() = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()).toJson(Json)

    private fun inputsOf(vararg pairs: Pair<String, PipelineValue>) = NodeInputs(mapOf(*pairs))

    // --- success: decodeOrigin returns non-null (origin present + value is JsonElement) ---

    @Test
    fun `decodes a value carrying an origin back to its typed form`() = runTest {
        val node = JsonToSerializableNode(id = "j2t")
        val result = node.executeForTestValue(context, inputsOf("in" to jsonWithOrigin))
        assertEquals(Person("Ada", "ada@x.io"), result.value, "the JSON should round-trip back to the typed Person")
    }

    @Test
    fun `the decoded value's serializer is the origin serializer`() = runTest {
        // decodeOrigin re-tags the value with the origin serializer; assert the descriptor name carries through.
        val node = JsonToSerializableNode(id = "j2t")
        val result = node.executeForTestValue(context, inputsOf("in" to jsonWithOrigin))
        assertEquals(Person.serializer().descriptor.serialName, result.serializer.descriptor.serialName)
    }

    @Test
    fun `uses the first inbound value when several are present`() = runTest {
        // inputs.first reads the first map value; a second port must not change the outcome.
        val node = JsonToSerializableNode(id = "j2t")
        val extra = PipelineValue.of(Person("Bob", "bob@x.io"), Person.serializer()).toJson(Json)
        val result = node.executeForTestValue(context, inputsOf("in" to jsonWithOrigin, "other" to extra))
        assertEquals(Person("Ada", "ada@x.io"), result.value)
    }

    // --- error: null input (inputs.first == null) ---

    @Test
    fun `empty inputs fail because there is no input`() = runTest {
        val node = JsonToSerializableNode(id = "lonely")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, NodeInputs(emptyMap())) }
        assertTrue(
            "requires an input" in (failure.message ?: ""),
            "expected the no-input error, got: ${failure.message}",
        )
        assertTrue("lonely" in (failure.message ?: ""), "the no-input error should name the node id")
    }

    // --- error: decodeOrigin returns null (no origin -> plain JSON) ---

    @Test
    fun `plain JSON with no origin fails and the message uses the id when name is blank`() = runTest {
        // ofJson carries no origin serializer, so decodeOrigin returns null -> error path.
        // name is blank here, so name.ifBlank { id } must fall through to the id.
        val node = JsonToSerializableNode(id = "plain-id")
        val plain = PipelineValue.ofJson(buildJsonObject { put("name", "Ada") })
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputsOf("in" to plain)) }
        assertTrue(
            "carries no origin type" in (failure.message ?: ""),
            "expected the no-origin error, got: ${failure.message}",
        )
        assertTrue("plain-id" in (failure.message ?: ""), "blank name should make the message fall back to the id")
    }

    @Test
    fun `plain JSON error message uses the name when name is set`() = runTest {
        // Same null-origin error path, but name is non-blank so name.ifBlank { id } keeps the name.
        val node = JsonToSerializableNode(id = "the-id", name = "Friendly Name")
        val plain = PipelineValue.ofJson(JsonPrimitive("just a string"))
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputsOf("in" to plain)) }
        assertTrue(
            "carries no origin type" in (failure.message ?: ""),
            "expected the no-origin error, got: ${failure.message}",
        )
        assertTrue("Friendly Name" in (failure.message ?: ""), "a set name should appear in the message")
        assertTrue("the-id" !in (failure.message ?: ""), "the id must not be used when a name is present")
    }

    // --- @Serializable data class: round-trip, minimal decode, per-field inequality ---

    @Test
    fun `round-trips through JSON preserving every field`() = runTest {
        val node = JsonToSerializableNode(
            id = "n1",
            name = "My Node",
            description = "does a thing",
            position = NodePosition(3.0, 4.0),
        )
        val encoded = Json.encodeToString(JsonToSerializableNode.serializer(), node)
        val decoded = Json.decodeFromString(JsonToSerializableNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `decodes minimal JSON exercising every default-value arm`() = runTest {
        // Only id is supplied; name/description/position must take their defaults.
        val decoded = Json.decodeFromString(
            JsonToSerializableNode.serializer(),
            """{"id":"only-id"}""",
        )
        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `differs from a copy that changes the id`() = runTest {
        val node = JsonToSerializableNode(id = "a")
        val encoded = Json.encodeToString(JsonToSerializableNode.serializer(), node)
        val other = Json.encodeToString(JsonToSerializableNode.serializer(), JsonToSerializableNode(id = "b"))
        assertTrue(encoded != other, "changing the id must change the serialized form")
    }

    @Test
    fun `differs from a copy that changes the name`() = runTest {
        val a = Json.encodeToString(JsonToSerializableNode.serializer(), JsonToSerializableNode(id = "x", name = "one"))
        val b = Json.encodeToString(JsonToSerializableNode.serializer(), JsonToSerializableNode(id = "x", name = "two"))
        assertTrue(a != b, "changing the name must change the serialized form")
    }

    @Test
    fun `differs from a copy that changes the description`() = runTest {
        val a = Json.encodeToString(
            JsonToSerializableNode.serializer(),
            JsonToSerializableNode(id = "x", description = "one"),
        )
        val b = Json.encodeToString(
            JsonToSerializableNode.serializer(),
            JsonToSerializableNode(id = "x", description = "two"),
        )
        assertTrue(a != b, "changing the description must change the serialized form")
    }

    @Test
    fun `differs from a copy that changes the position`() = runTest {
        val a = Json.encodeToString(
            JsonToSerializableNode.serializer(),
            JsonToSerializableNode(id = "x", position = NodePosition(1.0, 1.0)),
        )
        val b = Json.encodeToString(
            JsonToSerializableNode.serializer(),
            JsonToSerializableNode(id = "x", position = NodePosition(9.0, 9.0)),
        )
        assertTrue(a != b, "changing the position must change the serialized form")
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() = runTest {
        // id is the only no-default field; an empty object omits it, driving the generated
        // deserializer's `(seen & required) != required -> throwMissingFieldException` arm — the arm a
        // valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(JsonToSerializableNode.serializer(), "{}")
        }
    }
}

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.uuid
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUIDSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class GetIdNodeTest {

    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    private fun jsonInputs(element: kotlinx.serialization.json.JsonElement) =
        NodeInputs(mapOf("in" to PipelineValue.ofJson(element)))

    // --- extraction: object inputs (default and custom field) ---

    @Test
    fun `extracts the default id field from an object input`() = runTest {
        val id = Uuid.random()
        val node = GetIdNode(id = "extract")
        val result = node.executeForTestValue(context, jsonInputs(buildJsonObject { put("id", id.toString()) }))
        assertEquals(id, result.uuid(json), "the node emits the id as a typed UUID")
    }

    @Test
    fun `extracts a custom field from a typed event object`() = runTest {
        // A typed event carries a prefixed id field (e.g. taskId); `field` points the node at it.
        val id = Uuid.random()
        val node = GetIdNode(id = "extract", field = "taskId")
        val result = node.executeForTestValue(context, jsonInputs(buildJsonObject { put("taskId", id.toString()) }))
        assertEquals(id, result.uuid(json))
    }

    // --- extraction: bare-identifier inputs pass through ---

    @Test
    fun `a bare UUID string passes through`() = runTest {
        val id = Uuid.random()
        val node = GetIdNode(id = "extract")
        val result = node.executeForTestValue(context, jsonInputs(JsonPrimitive(id.toString())))
        assertEquals(id, result.uuid(json))
    }

    @Test
    fun `a typed UUID value passes through via its carried serializer`() = runTest {
        val id = Uuid.random()
        val node = GetIdNode(id = "extract")
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.of(id, UUIDSerializer()))))
        assertEquals(id, result.uuid(json))
    }

    @Test
    fun `the output declares its UUID origin`() = runTest {
        val node = GetIdNode(id = "extract")
        val result = node.executeForTestValue(context, jsonInputs(JsonPrimitive(Uuid.random().toString())))
        assertEquals(UUIDSerializer().descriptor.serialName, result.typeName)
    }

    // --- error: missing input (the generated codec's required-input message) ---

    @Test
    fun `no input fails with the generated required-input error`() = runTest {
        val node = GetIdNode(id = "extract")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, NodeInputs(emptyMap())) }
        assertTrue("required input 'in'" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun `run propagates the missing-input error`() = runTest {
        val node = GetIdNode(id = "extract")
        assertFailsWith<IllegalStateException> { node.run(context, NodeInputs(emptyMap())) }
    }

    // --- error: no extractable id (both ifBlank arms again) ---

    @Test
    fun `an input with neither the field nor a bare UUID raises an error when unnamed`() = runTest {
        val node = GetIdNode(id = "extract") // blank name
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, jsonInputs(buildJsonObject { put("name", "x") }))
        }
        assertTrue("extract" in (failure.message ?: ""), failure.message ?: "")
        assertTrue("no 'id' UUID" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun `an input missing the custom field raises an error naming the node name`() = runTest {
        val node = GetIdNode(id = "extract", name = "Task id", field = "taskId")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, jsonInputs(buildJsonObject { put("id", Uuid.random().toString()) }))
        }
        assertTrue("Task id" in (failure.message ?: ""), failure.message ?: "")
        assertTrue("no 'taskId' UUID" in (failure.message ?: ""), failure.message ?: "")
    }

    // --- run wrapping + dry run ---

    @Test
    fun `run wraps the extracted id as a NodeResult Output`() = runTest {
        val id = Uuid.random()
        val node = GetIdNode(id = "extract")
        val result = node.run(context, jsonInputs(buildJsonObject { put("id", id.toString()) }))
        assertTrue(result is NodeResult.Output, "the default run emits an Output")
        assertEquals(id, (result as NodeResult.Output).value?.uuid(json))
    }

    @Test
    fun `a dry run still extracts the id`() = runTest {
        val id = Uuid.random()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val node = GetIdNode(id = "extract")
        val result = node.executeForTestValue(dry, jsonInputs(buildJsonObject { put("id", id.toString()) }))
        assertEquals(id, result.uuid(json))
    }

    // --- node identity / defaults / serialization ---

    @Test
    fun `default constructor params default to empty name and description, id field, and origin position`() {
        val node = GetIdNode(id = "extract")
        assertEquals("extract", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals("id", node.field)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `explicit constructor params are retained`() {
        val node = GetIdNode(
            id = "n1",
            name = "Task id",
            description = "from the event",
            position = NodePosition(3.0, 4.0),
            field = "taskId",
        )
        assertEquals("n1", node.id)
        assertEquals("Task id", node.name)
        assertEquals("from the event", node.description)
        assertEquals("taskId", node.field)
        assertEquals(NodePosition(3.0, 4.0), node.position)
    }

    @Test
    fun `description and position pass through the defaulting constructor`() {
        // A synthetic (default) constructor call that supplies description and position while leaving
        // name and field at their defaults — covers those parameters' passed-value arms.
        val node = GetIdNode(id = "x", description = "why", position = NodePosition(9.0, 9.0))
        assertEquals("why", node.description)
        assertEquals(NodePosition(9.0, 9.0), node.position)
        assertEquals("", node.name)
        assertEquals("id", node.field)
    }

    @Test
    fun `node serializes to and from JSON via its explicit serializer`() {
        val node = GetIdNode(
            id = "n1",
            name = "Task id",
            description = "from the event",
            position = NodePosition(3.0, 4.0),
            field = "taskId",
        )
        val encoded = json.encodeToString(GetIdNode.serializer(), node)
        val decoded = json.decodeFromString(GetIdNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.field, decoded.field)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `encoding a default-valued node omits the defaulted fields and still round-trips`() {
        // Exercises the generated write$Self "skip default" arms (encodeDefaults is off): a node left
        // at its defaults serializes to just its id, and decodes back to the same defaults.
        val node = GetIdNode(id = "d")
        val encoded = json.encodeToString(GetIdNode.serializer(), node)
        assertTrue("name" !in encoded, encoded)
        assertTrue("field" !in encoded, encoded)
        assertTrue("position" !in encoded, encoded)
        val decoded = json.decodeFromString(GetIdNode.serializer(), encoded)
        assertEquals("d", decoded.id)
        assertEquals("id", decoded.field)
        assertEquals("", decoded.name)
    }

    @Test
    fun `node decodes from a minimal JSON exercising default-value arms`() {
        val decoded = json.decodeFromString(GetIdNode.serializer(), """{"id":"only-id"}""")
        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals("id", decoded.field)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `decoding JSON missing the required id throws`() {
        assertFailsWith<SerializationException> { json.decodeFromString(GetIdNode.serializer(), "{}") }
    }
}

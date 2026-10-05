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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectsToMapNodeTest {

    @Serializable
    private data class Profile(val name: String, val email: String)

    @Serializable
    private data class Attributes(val role: String, val active: Boolean)

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    private fun profileValue(name: String = "Ada", email: String = "ada@x.io") =
        PipelineValue.of(Profile(name, email), Profile.serializer())

    private fun attributesValue(role: String = "admin", active: Boolean = true) =
        PipelineValue.of(Attributes(role, active), Attributes.serializer())

    // --- zero iterations: empty inputs -> empty JSON object ---

    @Test
    fun `empty inputs produce an empty json object`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val result = node.executeForTestValue(context, NodeInputs(emptyMap()))
        val obj = (result.value as JsonObject)
        assertTrue(obj.isEmpty(), "no inbound ports should produce an empty object")
    }

    // --- one iteration: single named port ---

    @Test
    fun `a single inbound branch is keyed by its port`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val inputs = NodeInputs(mapOf("profile" to profileValue()))
        val result = node.executeForTestValue(context, inputs)
        val obj = result.value as JsonObject
        assertEquals(setOf("profile"), obj.keys)
        val profile = obj.getValue("profile").jsonObject
        assertEquals("Ada", profile.getValue("name").jsonPrimitive.content)
        assertEquals("ada@x.io", profile.getValue("email").jsonPrimitive.content)
    }

    // --- many iterations: multiple named ports, each encoded by its carried serializer ---

    @Test
    fun `multiple inbound branches are merged keyed by each port using its own serializer`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val inputs = NodeInputs(
            linkedMapOf(
                "profile" to profileValue("Grace", "grace@x.io"),
                "attributes" to attributesValue("operator", active = false),
            ),
        )
        val result = node.executeForTestValue(context, inputs)
        val obj = result.value as JsonObject
        assertEquals(setOf("profile", "attributes"), obj.keys)

        val profile = obj.getValue("profile").jsonObject
        assertEquals("Grace", profile.getValue("name").jsonPrimitive.content)
        assertEquals("grace@x.io", profile.getValue("email").jsonPrimitive.content)

        val attributes = obj.getValue("attributes").jsonObject
        assertEquals("operator", attributes.getValue("role").jsonPrimitive.content)
        assertEquals(false, attributes.getValue("active").jsonPrimitive.content.toBoolean())
    }

    // --- a port whose value is already JSON encodes through the JSON serializer unchanged ---

    @Test
    fun `a json-form inbound branch is keyed and carried through unchanged`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val inner = buildJsonObject { put("kind", "raw") }
        val inputs = NodeInputs(mapOf("payload" to PipelineValue.ofJson(inner)))
        val result = node.executeForTestValue(context, inputs)
        val obj = result.value as JsonObject
        assertEquals(inner, obj.getValue("payload"))
    }

    // --- mixed typed + json + scalar branches all land under their ports ---

    @Test
    fun `typed json and scalar branches all merge under their ports`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val inputs = NodeInputs(
            linkedMapOf(
                "profile" to profileValue(),
                "raw" to PipelineValue.ofJson(JsonPrimitive(42)),
                "label" to PipelineValue.of("hello", String.serializer()),
            ),
        )
        val result = node.executeForTestValue(context, inputs)
        val obj = result.value as JsonObject
        assertEquals(setOf("profile", "raw", "label"), obj.keys)
        assertEquals(42, obj.getValue("raw").jsonPrimitive.content.toInt())
        assertEquals("hello", obj.getValue("label").jsonPrimitive.content)
        assertTrue(obj.getValue("profile") is JsonObject)
    }

    // --- output is a JSON-form PipelineValue with no typed origin (shape-changing node) ---

    @Test
    fun `the output is plain json with no typed origin`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val inputs = NodeInputs(mapOf("profile" to profileValue()))
        val result = node.executeForTestValue(context, inputs)
        // ofJson carries no origin serializer, so a shape-changing node exposes no typed name.
        assertEquals(null, result.typeName, "ObjectsToMap output has no typed origin")
        assertEquals(null, result.port, "the merged value flows on every outbound edge")
        assertNull(result.decodeOrigin(Json))
    }

    // --- the default run() wrapper surfaces the merged object as NodeResult.Output ---

    @Test
    fun `run wraps the merged object as a NodeResult Output`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val inputs = NodeInputs(mapOf("profile" to profileValue()))
        val result = node.run(context, inputs)
        assertTrue(result is NodeResult.Output)
        val obj = (result as NodeResult.Output).value?.value as JsonObject
        assertEquals(setOf("profile"), obj.keys)
    }

    // --- a port name distinct from the model field names: keying is by port, not type ---

    @Test
    fun `branches keep their operator-given port names`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        val inputs = NodeInputs(
            linkedMapOf(
                "current" to profileValue("Now", "now@x.io"),
                "previous" to profileValue("Then", "then@x.io"),
            ),
        )
        val result = node.executeForTestValue(context, inputs)
        val obj = result.value as JsonObject
        assertEquals(setOf("current", "previous"), obj.keys)
        assertEquals("Now", obj.getValue("current").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("Then", obj.getValue("previous").jsonObject.getValue("name").jsonPrimitive.content)
    }

    // --- node identity / defaults are wired through the constructor ---

    @Test
    fun `node carries its id and default fields`() = runTest {
        val node = ObjectsToMapNode(id = "merge")
        assertEquals("merge", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `node accepts explicit name description and position`() = runTest {
        val node = ObjectsToMapNode(
            id = "merge",
            name = "Combine",
            description = "merge profile + attributes",
            position = NodePosition(10.0, 20.0),
        )
        assertEquals("Combine", node.name)
        assertEquals("merge profile + attributes", node.description)
        assertEquals(NodePosition(10.0, 20.0), node.position)
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() = runTest {
        // id is the only no-default field; an empty object omits it, driving the generated
        // deserializer's `(seen & required) != required -> throwMissingFieldException` arm — the arm a
        // valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ObjectsToMapNode.serializer(), "{}")
        }
    }
}

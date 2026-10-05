package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonataNodeTest {

    @Serializable
    private data class Person(val name: String, val email: String, val age: Int)

    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    private val person = Person("Ada", "ada@x.io", 36)
    private val typedValue = PipelineValue.of(person, Person.serializer())
    private fun typedInputs() = NodeInputs(mapOf("in" to typedValue))

    private fun jsonInputs(element: kotlinx.serialization.json.JsonElement) =
        NodeInputs(mapOf("in" to PipelineValue.ofJson(element)))

    // --- execute: error arm (null/empty input -> elvis error) ---

    @Test
    fun `execute with no inputs raises an error naming the node id`() = runTest {
        val node = JsonataNode(id = "shape", expression = "name")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue(
            "shape" in (failure.message ?: ""),
            "the error should name the node id, got: ${failure.message}",
        )
        assertTrue(
            "requires an input" in (failure.message ?: ""),
            "the error should explain the missing input, got: ${failure.message}",
        )
    }

    @Test
    fun `run propagates the missing-input error`() = runTest {
        val node = JsonataNode(id = "shape", expression = "name")
        assertFailsWith<IllegalStateException> {
            node.run(context, NodeInputs(emptyMap()))
        }
    }

    // --- execute: transform happy path (non-null input arm + String result arm) ---

    @Test
    fun `execute extracts a string field from a typed input`() = runTest {
        // The typed value is bridged to JSON via its carried serializer, then JSONata selects a field.
        val node = JsonataNode(id = "shape", expression = "name")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals(JsonPrimitive("Ada"), result.value)
    }

    @Test
    fun `execute extracts a string field from an already-JSON input`() = runTest {
        // Already-JSON input exercises the same path (encode returns the element unchanged).
        val node = JsonataNode(id = "shape", expression = "email")
        val element = buildJsonObject {
            put("name", "Grace")
            put("email", "grace@x.io")
        }
        val result = node.executeForTestValue(context, jsonInputs(element))
        assertEquals(JsonPrimitive("grace@x.io"), result.value)
    }

    // --- execute: each toJsonElement arm via a tailored expression ---

    @Test
    fun `a path selecting a missing field yields json null (the null value arm)`() = runTest {
        // A missing field evaluates to Java null -> JsonNull.
        val node = JsonataNode(id = "shape", expression = "nope")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals(JsonNull, result.value)
    }

    @Test
    fun `a literal null expression yields json null (the NULL_VALUE arm)`() = runTest {
        // The JSONata `null` literal evaluates to Jsonata.NULL_VALUE -> JsonNull.
        val node = JsonataNode(id = "shape", expression = "null")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals(JsonNull, result.value)
    }

    @Test
    fun `building an object yields a json object (the Map arm, recursing into children)`() = runTest {
        val node = JsonataNode(
            id = "shape",
            expression = "{ \"full\": name, \"contact\": email }",
        )
        val result = node.executeForTestValue(context, typedInputs())
        val obj = result.value as JsonObject
        assertEquals(setOf("full", "contact"), obj.keys)
        assertEquals("Ada", obj.getValue("full").jsonPrimitive.content)
        assertEquals("ada@x.io", obj.getValue("contact").jsonPrimitive.content)
    }

    @Test
    fun `building an array yields a json array (the List arm, recursing into elements)`() = runTest {
        val node = JsonataNode(id = "shape", expression = "[name, email]")
        val result = node.executeForTestValue(context, typedInputs())
        val arr = result.value as JsonArray
        assertEquals(2, arr.size)
        assertEquals("Ada", arr[0].jsonPrimitive.content)
        assertEquals("ada@x.io", arr[1].jsonPrimitive.content)
    }

    @Test
    fun `a boolean comparison yields a json boolean (the Boolean arm)`() = runTest {
        val node = JsonataNode(id = "shape", expression = "age > 30")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals(JsonPrimitive(true), result.value)
        assertEquals(true, (result.value as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `a numeric field yields a json number (the Number arm)`() = runTest {
        val node = JsonataNode(id = "shape", expression = "age")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals(36, (result.value as JsonPrimitive).content.toInt())
    }

    @Test
    fun `arithmetic on a numeric field yields a json number`() = runTest {
        val node = JsonataNode(id = "shape", expression = "age + 4")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals(40, (result.value as JsonPrimitive).content.toInt())
    }

    @Test
    fun `string concatenation yields a json string (the String arm)`() = runTest {
        val node = JsonataNode(id = "shape", expression = "name & \" <\" & email & \">\"")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals("Ada <ada@x.io>", (result.value as JsonPrimitive).content)
    }

    @Test
    fun `nested objects and arrays recurse through both container arms`() = runTest {
        // A nested structure forces toJsonElement recursion: Map -> List -> primitives.
        val node = JsonataNode(
            id = "shape",
            expression = "{ \"tags\": [name, email], \"meta\": { \"old\": age >= 18 } }",
        )
        val result = node.executeForTestValue(context, typedInputs())
        val obj = result.value as JsonObject
        val tags = obj.getValue("tags").jsonArray
        assertEquals(listOf("Ada", "ada@x.io"), tags.map { it.jsonPrimitive.content })
        val meta = obj.getValue("meta").jsonObject
        assertEquals(JsonPrimitive(true), meta.getValue("old"))
    }

    // --- execute: $eventCreated binding ---

    @Test
    fun `the expression can reference the bound eventCreated variable`() = runTest {
        // execute binds `eventCreated` to context.inputCreated.toString(); a JSONata var read returns it.
        val created = OffsetDateTime.now()
        val ctx = PipelineContext(AuthenticationContext(null, null), json, inputCreated = created)
        val node = JsonataNode(id = "shape", expression = "\$eventCreated")
        val result = node.executeForTestValue(ctx, typedInputs())
        assertEquals(created.toString(), (result.value as JsonPrimitive).content)
    }

    @Test
    fun `eventCreated can be embedded into a built object`() = runTest {
        val created = OffsetDateTime.now()
        val ctx = PipelineContext(AuthenticationContext(null, null), json, inputCreated = created)
        val node = JsonataNode(
            id = "shape",
            expression = "{ \"who\": name, \"when\": \$eventCreated }",
        )
        val result = node.executeForTestValue(ctx, typedInputs())
        val obj = result.value as JsonObject
        assertEquals("Ada", obj.getValue("who").jsonPrimitive.content)
        assertEquals(created.toString(), obj.getValue("when").jsonPrimitive.content)
    }

    // --- execute: invalid expression error path (Jsonata.jsonata throws) ---

    @Test
    fun `an invalid expression fails the run`() = runTest {
        // dashjoin JSONata raises a JException (a RuntimeException) for an unparseable expression.
        val node = JsonataNode(id = "shape", expression = "{ broken")
        assertFailsWith<RuntimeException> {
            node.executeForTestValue(context, typedInputs())
        }
    }

    @Test
    fun `an empty expression fails the run`() = runTest {
        val node = JsonataNode(id = "shape", expression = "")
        assertFailsWith<RuntimeException> {
            node.executeForTestValue(context, typedInputs())
        }
    }

    // --- output shape: plain JSON, no typed origin, flows on every edge ---

    @Test
    fun `the output is plain json with no typed origin and no port`() = runTest {
        val node = JsonataNode(id = "shape", expression = "name")
        val result = node.executeForTestValue(context, typedInputs())
        // ofJson carries no origin serializer: a shape-changing node exposes no typed name.
        assertNull(result.typeName, "JSONata output has no typed origin")
        assertNull(result.port, "the shaped value flows on every outbound edge")
        assertNull(result.decodeOrigin(json))
    }

    // --- run: default wraps execute as NodeResult.Output ---

    @Test
    fun `run wraps the shaped value as a NodeResult Output`() = runTest {
        val node = JsonataNode(id = "shape", expression = "name")
        val result = node.run(context, typedInputs())
        assertTrue(result is NodeResult.Output, "the default run should emit an Output")
        assertEquals(JsonPrimitive("Ada"), (result as NodeResult.Output).value?.value)
    }

    // --- dry run: a pure transform still computes (no side effects to skip) ---

    @Test
    fun `a dry run still computes the transform`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val node = JsonataNode(id = "shape", expression = "name")
        val result = node.executeForTestValue(dry, typedInputs())
        assertEquals(JsonPrimitive("Ada"), result.value)
    }

    @Test
    fun `declared output metadata is projected and a specific type implies object kind`() {
        val schema = buildJsonObject { put("type", "string") }
        val typed = JsonataNode(
            id = "shape",
            expression = "name",
            outputKind = SlotKind.ANY,
            outputType = "sample.Name",
            outputSchema = schema,
        )
        val scalar = JsonataNode(id = "scalar", expression = "age", outputKind = SlotKind.NUMBER)

        assertEquals(SlotKind.OBJECT, typed.declaredOutputKind)
        assertEquals("sample.Name", typed.declaredOutputType)
        assertEquals(schema, typed.declaredOutputSchema)
        assertEquals(SlotKind.NUMBER, scalar.declaredOutputKind)
    }

    @Test
    fun `declared output schema accepts matching output and rejects a mismatch`() = runTest {
        val stringSchema = buildJsonObject { put("type", "string") }
        val objectSchema = buildJsonObject { put("type", "object") }

        val valid = JsonataNode("valid", expression = "name", outputSchema = stringSchema)
            .executeForTestValue(context, typedInputs())
        assertEquals(JsonPrimitive("Ada"), valid.value)

        val failure = assertFailsWith<IllegalStateException> {
            JsonataNode("invalid", name = "Shape", expression = "name", outputSchema = objectSchema)
                .executeForTestValue(context, typedInputs())
        }
        assertTrue(failure.message!!.contains("Shape"))
        assertTrue(failure.message!!.contains("declared schema"))
    }

    // --- node identity / defaults ---

    @Test
    fun `default constructor params default to empty name and description and origin position`() {
        val node = JsonataNode(id = "shape", expression = "name")
        assertEquals("shape", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals("name", node.expression)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `explicit constructor params are retained`() {
        val node = JsonataNode(
            id = "n1",
            name = "Shape",
            description = "map fields",
            expression = "$",
            position = NodePosition(3.0, 4.0),
        )
        assertEquals("n1", node.id)
        assertEquals("Shape", node.name)
        assertEquals("map fields", node.description)
        assertEquals("$", node.expression)
        assertEquals(NodePosition(3.0, 4.0), node.position)
    }

    // --- serialization round-trips via the explicit serializer ---

    @Test
    fun `node serializes to and from JSON via its explicit serializer`() {
        val node = JsonataNode(
            id = "n1",
            name = "Shape",
            description = "map fields",
            expression = "{ \"x\": y }",
            position = NodePosition(3.0, 4.0),
        )
        val encoded = json.encodeToString(JsonataNode.serializer(), node)
        val decoded = json.decodeFromString(JsonataNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.expression, decoded.expression)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node decodes from a minimal JSON exercising default-value arms`() {
        // Only id and expression are required; name, description, position fall back to defaults.
        val decoded = json.decodeFromString(
            JsonataNode.serializer(),
            """{"id":"only-id","expression":"name"}""",
        )
        assertEquals("only-id", decoded.id)
        assertEquals("name", decoded.expression)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
    }

    // --- a transform fed a scalar (non-object) JSON input ---

    @Test
    fun `the identity expression returns the whole input`() = runTest {
        // `$` is the input root: a scalar string input comes back as a json string.
        val node = JsonataNode(id = "shape", expression = "$")
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.of("hello", String.serializer()))))
        assertEquals(JsonPrimitive("hello"), result.value)
    }

    @Test
    fun `mapping over an array input yields a json array`() = runTest {
        // `$.( ... )` maps the expression over each element of the array context (a bare `$ * 2`
        // would try to multiply the whole array, which JSONata rejects).
        val node = JsonataNode(id = "shape", expression = "$.($ * 2)")
        val arrayInput = buildJsonArray {
            add(JsonPrimitive(1))
            add(JsonPrimitive(2))
            add(JsonPrimitive(3))
        }
        val result = node.executeForTestValue(context, jsonInputs(arrayInput))
        val arr = result.value as JsonArray
        assertEquals(listOf(2, 4, 6), arr.map { it.jsonPrimitive.content.toInt() })
    }

    // --- toJsonElement: the String arm explicitly (a literal string expression) ---

    @Test
    fun `a string literal expression yields a json string (the String arm)`() = runTest {
        // A bare string literal evaluates to a Java String -> JsonPrimitive(value) (String arm).
        val node = JsonataNode(id = "shape", expression = "\"a literal\"")
        val result = node.executeForTestValue(context, typedInputs())
        assertEquals(JsonPrimitive("a literal"), result.value)
        assertEquals("a literal", (result.value as JsonPrimitive).content)
    }

    // --- toJsonElement: the NULL_VALUE arm via the dedicated `null` literal again, on JSON input ---

    @Test
    fun `the NULL_VALUE arm is reached for a json-input null literal`() = runTest {
        // The `Jsonata.NULL_VALUE` half of the first when-arm, reached from an already-JSON input too.
        val node = JsonataNode(id = "shape", expression = "null")
        val result = node.executeForTestValue(context, jsonInputs(buildJsonObject { put("k", "v") }))
        assertEquals(JsonNull, result.value)
    }

    // --- toJsonElement: the else fallback arm (value is a function object -> toString) ---

    // --- generated deserializer: the throwMissingFieldException arm (a required field absent) ---

    @Test
    fun `decoding JSON missing a required field throws`() = runTest {
        // id and expression are required (no defaults); an empty object omits them, driving the
        // generated deserializer's `(seen & required) != required -> throwMissingFieldException` arm —
        // the arm a valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            json.decodeFromString(JsonataNode.serializer(), "{}")
        }
    }

    @Test
    fun `a function-valued result falls through to the toString else arm`() = runTest {
        // Referencing a JSONata function by name (no call) yields a function object, which is none of
        // null/Map/List/Boolean/Number/String -> the `else -> JsonPrimitive(value.toString())` arm.
        val node = JsonataNode(id = "shape", expression = "\$sum")
        val result = node.executeForTestValue(context, typedInputs())
        val primitive = result.value as JsonPrimitive
        // The exact toString of the function object is implementation-defined; assert it is a non-empty
        // string primitive (the else arm produced a JsonPrimitive(String), not an object/array/null).
        assertTrue(primitive.isString, "the else arm wraps the value's toString as a string primitive")
        assertTrue(primitive.content.isNotEmpty(), "a function toString is non-empty: ${primitive.content}")
    }
}

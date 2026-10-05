package bosca.pipelines.node
import bosca.pipelines.testutil.executeForTest

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.service.PipelineRunNodeResult
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Branch coverage for the node-contract types in `core-pipelines/node`: [JsonSchemaValidator],
 * [InputNode]/[OutputNode], [EntityReference] + [PipelineValue.entityReference],
 * [NodeDescriptor]/[NodeInputSlot]/[NodeOutputSlot] and [PipelineValue].
 */
class NodeCoverageTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val json = Json

    private fun parse(s: String): JsonElement = Json.parseToJsonElement(s)

    // =====================================================================================
    // JsonSchemaValidator
    // =====================================================================================

    @Test
    fun `a non-object schema validates nothing`() {
        // schema !is JsonObject -> emptyList()
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive(1), JsonPrimitive("not-a-schema")))
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonNull, parse("[]")))
    }

    @Test
    fun `an empty schema object accepts any value`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{"x":1}"""), parse("{}")))
    }

    @Test
    fun `type object matches an object and rejects a scalar`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{}"""), parse("""{"type":"object"}""")))
        val bad = JsonSchemaValidator.validate(JsonPrimitive(1), parse("""{"type":"object"}"""))
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected object"), bad.single())
    }

    @Test
    fun `type array matches a list and rejects an object`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("[]"), parse("""{"type":"array"}""")))
        val bad = JsonSchemaValidator.validate(parse("{}"), parse("""{"type":"array"}"""))
        assertTrue(bad.single().contains("expected array"), bad.single())
    }

    @Test
    fun `type string matches a string and rejects a number`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive("hi"), parse("""{"type":"string"}""")))
        val bad = JsonSchemaValidator.validate(JsonPrimitive(1), parse("""{"type":"string"}"""))
        assertTrue(bad.single().contains("expected string"), bad.single())
    }

    @Test
    fun `type number matches a double and rejects a string`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive(1.5), parse("""{"type":"number"}""")))
        val bad = JsonSchemaValidator.validate(JsonPrimitive("1.5"), parse("""{"type":"number"}"""))
        assertTrue(bad.single().contains("expected number"), bad.single())
    }

    @Test
    fun `type integer matches an int and rejects a string`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive(3), parse("""{"type":"integer"}""")))
        val bad = JsonSchemaValidator.validate(JsonPrimitive("3"), parse("""{"type":"integer"}"""))
        assertTrue(bad.single().contains("expected integer"), bad.single())
    }

    @Test
    fun `type boolean matches a boolean and rejects a string`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive(true), parse("""{"type":"boolean"}""")))
        val bad = JsonSchemaValidator.validate(JsonPrimitive("true"), parse("""{"type":"boolean"}"""))
        assertTrue(bad.single().contains("expected boolean"), bad.single())
    }

    @Test
    fun `type null matches JsonNull and rejects a value`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonNull, parse("""{"type":"null"}""")))
        val bad = JsonSchemaValidator.validate(JsonPrimitive(1), parse("""{"type":"null"}"""))
        assertTrue(bad.single().contains("expected null"), bad.single())
    }

    @Test
    fun `an unknown type keyword matches everything`() {
        // matchesType else -> true
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive(1), parse("""{"type":"weird"}""")))
    }

    @Test
    fun `describe and primitiveKind cover every primitive flavor in the message`() {
        // type=null forces describe over each flavor via a deliberately-wrong type.
        assertTrue(JsonSchemaValidator.validate(parse("{}"), parse("""{"type":"string"}""")).single().contains("got an object"))
        assertTrue(JsonSchemaValidator.validate(parse("[]"), parse("""{"type":"string"}""")).single().contains("got an array"))
        assertTrue(JsonSchemaValidator.validate(JsonNull, parse("""{"type":"string"}""")).single().contains("got null"))
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive("s"), parse("""{"type":"integer"}""")).single().contains("got a string"))
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive(true), parse("""{"type":"string"}""")).single().contains("got a boolean"))
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive(7), parse("""{"type":"string"}""")).single().contains("got a integer"))
        assertTrue(JsonSchemaValidator.validate(JsonPrimitive(7.5), parse("""{"type":"string"}""")).single().contains("got a number"))
    }

    @Test
    fun `enum accepts a member and rejects a non-member`() {
        val schema = parse("""{"enum":["a","b"]}""")
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive("a"), schema))
        val bad = JsonSchemaValidator.validate(JsonPrimitive("c"), schema)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("must be one of"), bad.single())
    }

    @Test
    fun `enum that is not an array is ignored`() {
        // schema["enum"] as? JsonArray -> null arm
        assertEquals(emptyList(), JsonSchemaValidator.validate(JsonPrimitive("x"), parse("""{"enum":"notarray"}""")))
    }

    @Test
    fun `required reports each missing field and passes when present`() {
        val schema = parse("""{"type":"object","required":["a","b"]}""")
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{"a":1,"b":2}"""), schema))
        val bad = JsonSchemaValidator.validate(parse("""{"a":1}"""), schema)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("missing required field 'b'"), bad.single())
    }

    @Test
    fun `required that is not an array is treated as empty`() {
        // (schema["required"] as? JsonArray) -> null -> orEmpty()
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{"a":1}"""), parse("""{"type":"object","required":"a"}""")))
    }

    @Test
    fun `an object schema with no properties only checks required`() {
        // properties as? JsonObject ?: return  (the early return arm)
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{"a":1,"extra":2}"""), parse("""{"type":"object","required":["a"]}""")))
    }

    @Test
    fun `properties are validated recursively and unknown keys are skipped`() {
        val schema = parse("""{"type":"object","properties":{"age":{"type":"integer"}}}""")
        // 'name' has no property schema -> the `continue` arm; 'age' is wrong -> recursion violation.
        val bad = JsonSchemaValidator.validate(parse("""{"name":"Ada","age":"old"}"""), schema)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("\$.age: expected integer"), bad.single())
    }

    @Test
    fun `a property whose schema is not an object is skipped`() {
        // properties[key] as? JsonObject ?: continue
        val schema = parse("""{"type":"object","properties":{"age":true}}""")
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{"age":"anything"}"""), schema))
    }

    @Test
    fun `a null optional property is skipped but a null required property is validated`() {
        val optional = parse("""{"type":"object","properties":{"age":{"type":"integer"}}}""")
        // age is null and not required -> the `continue` arm, no violation.
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{"age":null}"""), optional))

        val required = parse("""{"type":"object","required":["age"],"properties":{"age":{"type":"integer"}}}""")
        // age is null and required -> validated, type integer fails on null.
        val bad = JsonSchemaValidator.validate(parse("""{"age":null}"""), required)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("\$.age: expected integer"), bad.single())
    }

    @Test
    fun `items validate each element across zero one and many`() {
        val schema = parse("""{"type":"array","items":{"type":"integer"}}""")
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("[]"), schema))                    // zero
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("[1]"), schema))                   // one
        val bad = JsonSchemaValidator.validate(parse("""[1,"two",3,"four"]"""), schema)                 // many
        assertEquals(2, bad.size, bad.toString())
        assertTrue(bad.any { it.contains("[1]: expected integer") }, bad.toString())
        assertTrue(bad.any { it.contains("[3]: expected integer") }, bad.toString())
    }

    @Test
    fun `an array schema without items checks only the array type`() {
        // itemSchema as? JsonObject ?: return
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""[1,"two",{}]"""), parse("""{"type":"array"}""")))
    }

    @Test
    fun `an array schema whose items is not an object is ignored`() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("[1,2]"), parse("""{"type":"array","items":3}""")))
    }

    @Test
    fun `nested object inside array reports a deep path`() {
        val schema = parse("""{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"}}}}""")
        val bad = JsonSchemaValidator.validate(parse("""[{"id":"x"}]"""), schema)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("\$[0].id: expected integer"), bad.single())
    }

    // =====================================================================================
    // InputNode / OutputNode
    // =====================================================================================

    @Test
    fun `InputNode execute returns the first input or null when empty`() = runTest {
        val node = InputNode(id = "n1", acceptedType = "io.bosca.SomeEvent")
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val value = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        assertSame(value, node.executeForTest(ctx, NodeInputs(mapOf("in" to value))))
        assertNull(node.executeForTest(ctx, NodeInputs(emptyMap())))
    }

    @Test
    fun `InputNode run wraps execute as an Output result`() = runTest {
        val node = InputNode(id = "n1", acceptedType = "io.bosca.SomeEvent")
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val value = PipelineValue.of(1, Int.serializer())
        val result = node.run(ctx, NodeInputs(mapOf("in" to value)))
        assertTrue(result is NodeResult.Output)
        assertSame(value, (result as NodeResult.Output).value)
    }

    @Test
    fun `InputNode JSON_TYPE sentinel constant`() {
        assertEquals("JSON", InputNode.JSON_TYPE)
    }

    @Test
    fun `InputNode round-trips through its explicit serializer with defaults and an explicit schema`() {
        val schema = buildJsonObject { put("type", "object") }
        val node = InputNode(
            id = "n1",
            name = "Start",
            description = "entry",
            acceptedType = InputNode.JSON_TYPE,
            schema = schema,
            fields = listOf(ShapeField("id", "String")),
            position = NodePosition(1.0, 2.0),
        )
        val encoded = json.encodeToString(InputNode.serializer(), node)
        val decoded = json.decodeFromString(InputNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.acceptedType, decoded.acceptedType)
        assertEquals(node.schema, decoded.schema)
        assertEquals(listOf("id" to "String"), decoded.fields.map { it.name to it.type })
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `InputNode decodes from minimal JSON exercising every default-value arm`() {
        val decoded = json.decodeFromString(InputNode.serializer(), """{"id":"n1","acceptedType":"io.bosca.E"}""")
        assertEquals("n1", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals("io.bosca.E", decoded.acceptedType)
        assertNull(decoded.schema)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `InputNode shape declarations are object outputs and dry-run as a passthrough`() = runTest {
        val node = InputNode(id = "shape", acceptedType = InputNode.SHAPE_TYPE)
        assertEquals(SlotKind.OBJECT, node.declaredOutputKind)
        assertEquals("", node.declaredOutputType)

        val value = PipelineValue.ofJson(JsonPrimitive("value"))
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true)
        val result = node.run(dry, NodeInputs(mapOf("in" to value)))
        assertTrue(result is NodeResult.Output)
        assertSame(value, result.value)
    }

    @Test
    fun `InputNode and OutputNode reject missing required identifiers`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(InputNode.serializer(), """{"acceptedType":"JSON"}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(OutputNode.serializer(), "{}")
        }
    }

    @Test
    fun `OutputNode execute returns the first input or null`() = runTest {
        val node = OutputNode(id = "o1")
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val value = PipelineValue.of("done", String.serializer())
        assertSame(value, node.executeForTest(ctx, NodeInputs(mapOf("in" to value))))
        assertNull(node.executeForTest(ctx, NodeInputs(emptyMap())))
    }

    @Test
    fun `OutputNode round-trips and decodes from minimal JSON`() {
        val schema = buildJsonObject { put("type", "string") }
        val node = OutputNode(
            id = "o1",
            name = "End",
            description = "exit",
            outputType = "JSON",
            schema = schema,
            fields = listOf(ShapeField("value", "String")),
            position = NodePosition(3.0, 4.0),
        )
        val encoded = json.encodeToString(OutputNode.serializer(), node)
        val decoded = json.decodeFromString(OutputNode.serializer(), encoded)
        assertEquals(node.outputType, decoded.outputType)
        assertEquals(node.schema, decoded.schema)
        assertEquals(listOf("value" to "String"), decoded.fields.map { it.name to it.type })
        assertEquals(node.position, decoded.position)

        val minimal = json.decodeFromString(OutputNode.serializer(), """{"id":"o1"}""")
        assertEquals("", minimal.name)
        assertEquals("", minimal.description)
        assertEquals("", minimal.outputType)
        assertNull(minimal.schema)
        assertEquals(NodePosition(), minimal.position)
    }

    // =====================================================================================
    // PipelineRunNodeResult
    // =====================================================================================

    @Test
    fun `PipelineRunNodeResult round-trips and differs by field`() {
        val r = PipelineRunNodeResult(JsonPrimitive("x"), port = "out")
        val encoded = json.encodeToString(PipelineRunNodeResult.serializer(), r)
        val decoded = json.decodeFromString(PipelineRunNodeResult.serializer(), encoded)
        assertEquals(r, decoded)
        assertNotEquals(r, r.copy(value = JsonPrimitive("y")))
        assertNotEquals(r, r.copy(port = "other"))
        assertNotEquals(r, r.copy(port = null))
        // default port arm:
        val noPort = json.decodeFromString(PipelineRunNodeResult.serializer(), """{"value":"x"}""")
        assertNull(noPort.port)
    }

    // =====================================================================================
    // EntityReference + PipelineValue.entityReference
    // =====================================================================================

    @Serializable
    private data class TypedEntity(val id: String, val version: Int)

    private val sampleUuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    @Test
    fun `EntityReference holds an id and an optional version`() {
        val id = UUID.parse(sampleUuid)
        assertEquals(id, EntityReference(id).id)
        assertNull(EntityReference(id).version)
        assertEquals(5, EntityReference(id, 5).version)
        assertEquals(NodePosition(1.0, 0.0), NodePosition(x = 1.0))
        assertEquals(NodePosition(0.0, 2.0), NodePosition(y = 2.0))
        assertEquals(NodePosition(1.0, 5.0), NodePosition(1.0, 2.0).copy(y = 5.0))
    }

    @Test
    fun `entityReference returns null when the value is not an object`() {
        val scalar = PipelineValue.of(7, Int.serializer())
        assertNull(scalar.entityReference(json))
    }

    @Test
    fun `entityReference extracts id and version from a typed object`() {
        val value = PipelineValue.of(TypedEntity(sampleUuid, 9), TypedEntity.serializer())
        val ref = value.entityReference(json)
        assertNotNull(ref)
        assertEquals(UUID.parse(sampleUuid), ref.id)
        assertEquals(9, ref.version)
    }

    @Test
    fun `entityReference returns null version when the version field is absent`() {
        val value = PipelineValue.ofJson(buildJsonObject { put("id", sampleUuid) })
        val ref = value.entityReference(json)
        assertNotNull(ref)
        assertNull(ref.version)
    }

    @Test
    fun `entityReference tries id fields in order using the first that parses`() {
        // taskId tried first; id present too but taskId wins.
        val value = PipelineValue.ofJson(buildJsonObject {
            put("taskId", sampleUuid)
            put("id", "11111111-1111-1111-1111-111111111111")
        })
        val ref = value.entityReference(json, idFields = listOf("taskId", "id"))
        assertNotNull(ref)
        assertEquals(UUID.parse(sampleUuid), ref.id)
    }

    @Test
    fun `entityReference skips a non-parseable field and falls through to the next`() {
        // taskId present but not a UUID (catch arm); id is a valid UUID -> used.
        val value = PipelineValue.ofJson(buildJsonObject {
            put("taskId", "not-a-uuid")
            put("id", sampleUuid)
        })
        val ref = value.entityReference(json, idFields = listOf("taskId", "id"))
        assertNotNull(ref)
        assertEquals(UUID.parse(sampleUuid), ref.id)
    }

    @Test
    fun `entityReference skips a structured id candidate and falls through to the next`() {
        val value = PipelineValue.ofJson(buildJsonObject {
            put("taskId", buildJsonObject { put("nested", true) })
            put("id", sampleUuid)
        })

        assertEquals(
            UUID.parse(sampleUuid),
            value.entityReference(json, idFields = listOf("taskId", "id"))?.id,
        )
    }

    @Test
    fun `entityReference returns null when no id field matches`() {
        val value = PipelineValue.ofJson(buildJsonObject { put("name", "Ada") })
        assertNull(value.entityReference(json))
    }

    @Test
    fun `entityReference returns null when the only id field is not a string`() {
        // (obj[field] as? JsonPrimitive) is non-null for a number, but parse fails -> catch -> null.
        val value = PipelineValue.ofJson(buildJsonObject { put("id", 42) })
        assertNull(value.entityReference(json))
    }

    @Test
    fun `entityReference honors a custom version field name`() {
        val value = PipelineValue.ofJson(buildJsonObject {
            put("id", sampleUuid)
            put("rev", 3)
        })
        val ref = value.entityReference(json, versionField = "rev")
        assertNotNull(ref)
        assertEquals(3, ref.version)
    }

    // =====================================================================================
    // NodeDescriptor / NodeInputSlot / NodeOutputSlot
    // =====================================================================================

    @Test
    fun `NodeInputSlot defaults and explicit values`() {
        val def = NodeInputSlot(name = "a", typeLabel = "ANY")
        assertEquals(SlotKind.ANY, def.kind)
        assertNull(def.type)
        assertNull(def.schema)
        assertTrue(def.required)

        val explicit = NodeInputSlot(
            name = "b",
            typeLabel = "OBJECT",
            kind = SlotKind.OBJECT,
            type = "io.bosca.Profile",
            schema = buildJsonObject { put("type", "object") },
            required = false,
        )
        assertEquals(SlotKind.OBJECT, explicit.kind)
        assertEquals("io.bosca.Profile", explicit.type)
        assertNotNull(explicit.schema)
        assertFalse(explicit.required)
    }

    @Test
    fun `NodeOutputSlot defaults and an error port`() {
        val def = NodeOutputSlot(name = "out")
        assertEquals(SlotKind.ANY, def.kind)
        assertFalse(def.error)

        val err = NodeOutputSlot(name = "error", kind = SlotKind.OBJECT, error = true)
        assertEquals(SlotKind.OBJECT, err.kind)
        assertTrue(err.error)
    }

    @Test
    fun `NodeDescriptor defaults and a fully-specified descriptor`() {
        val def = NodeDescriptor(key = "k", label = "L", category = NodeCategory.TRANSFORM)
        assertEquals(emptyList(), def.inputs)
        assertFalse(def.variadic)
        assertEquals(emptyList(), def.outputs)
        assertEquals("", def.description)

        val full = NodeDescriptor(
            key = "k2",
            label = "L2",
            category = NodeCategory.ACTION,
            inputs = listOf(NodeInputSlot(name = "in", typeLabel = "ANY")),
            variadic = true,
            outputs = listOf(NodeOutputSlot(name = "out", kind = SlotKind.OBJECT, typeLabel = "Profile"), NodeOutputSlot(name = "error", error = true)),
            description = "does a thing",
        )
        assertEquals(1, full.inputs.size)
        assertTrue(full.variadic)
        assertEquals("Profile", full.outputs.first().typeLabel)
        assertEquals(SlotKind.OBJECT, full.outputs.first().kind)
        assertEquals(2, full.outputs.size)
        assertEquals("does a thing", full.description)
    }

    // =====================================================================================
    // PipelineValue
    // =====================================================================================

    @Test
    fun `of carries a typed value and encode serializes it`() {
        val value = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        assertEquals(Person("Ada", "ada@x.io"), value.value)
        val element = value.encode(json)
        assertTrue(element is JsonObject)
        assertEquals("ada@x.io", (element["email"] as JsonPrimitive).content)
    }

    @Test
    fun `ofJson wraps an existing element and reports no typeName`() {
        val element = buildJsonObject { put("email", "ada@x.io") }
        val value = PipelineValue.ofJson(element)
        assertSame(element, value.value)
        // value is JsonElement and no origin -> typeName null
        assertNull(value.typeName)
        // encode of a JSON value returns the element itself
        assertEquals(element, value.encode(json))
    }

    @Test
    fun `typeName reads the carried serializer when the value is typed`() {
        val value = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        assertEquals(Person.serializer().descriptor.serialName, value.typeName)
    }

    @Test
    fun `typeName prefers the origin serializer after toJson`() {
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val asJson = typed.toJson(json)
        // value is now JSON but originSerializer is retained -> typeName from origin
        assertEquals(Person.serializer().descriptor.serialName, asJson.typeName)
    }

    @Test
    fun `onPort emits the same value on a named port preserving origin`() {
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()).toJson(json)
        val ported = typed.onPort("approved")
        assertEquals("approved", ported.port)
        assertSame(typed.value, ported.value)
        // origin retained across onPort
        assertEquals(Person.serializer().descriptor.serialName, ported.typeName)
    }

    @Test
    fun `toJson converts a typed value and is a no-op for an already-json value`() {
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val asJson = typed.toJson(json)
        assertTrue(asJson.value is JsonElement)

        val alreadyJson = PipelineValue.ofJson(buildJsonObject { put("x", 1) })
        // value is JsonElement -> returns `this`
        assertSame(alreadyJson, alreadyJson.toJson(json))
    }

    @Test
    fun `toJson on a typed value with a port keeps the port and uses serializer as origin`() {
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()).onPort("out")
        val asJson = typed.toJson(json)
        assertEquals("out", asJson.port)
        // originSerializer was null before toJson -> the elvis used `serializer`, so origin is now set
        assertEquals(Person.serializer().descriptor.serialName, asJson.typeName)
    }

    @Test
    fun `toJson preserves an existing origin serializer across another representation change`() {
        @Suppress("UNCHECKED_CAST")
        val value = PipelineValue(
            value = 42,
            serializer = Int.serializer() as kotlinx.serialization.KSerializer<Any?>,
            originSerializer = Person.serializer() as kotlinx.serialization.KSerializer<Any?>,
        )

        val asJson = value.toJson(json)

        assertEquals(JsonPrimitive(42), asJson.value)
        assertEquals(Person.serializer().descriptor.serialName, asJson.typeName)
    }

    @Test
    fun `decodeOrigin round-trips a typed value back to its original type`() {
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val asJson = typed.toJson(json)
        val back = asJson.decodeOrigin(json)
        assertNotNull(back)
        assertEquals(Person("Ada", "ada@x.io"), back.value)
    }

    @Test
    fun `decodeOrigin returns null for plain JSON with no origin`() {
        val plain = PipelineValue.ofJson(buildJsonObject { put("x", 1) })
        // originSerializer == null -> null
        assertNull(plain.decodeOrigin(json))
    }

    @Test
    fun `decodeOrigin returns null when the value is not a JsonElement`() {
        // Construct a value that carries an origin serializer yet whose value is not JSON.
        // toJson then mutate: encode keeps origin but value becomes JSON, so instead build the
        // pathological case via a typed value whose toJson we skip — origin present, value typed.
        // typeName-origin path requires originSerializer; the only public way to set origin is toJson,
        // which always makes value a JsonElement. So decodeOrigin's "value !is JsonElement" arm is
        // reachable only if origin is set without value being JSON — not constructible publicly.
        // We assert the reachable arm instead: a typed value (no origin) yields null.
        val typed = PipelineValue.of(42, Int.serializer())
        assertNull(typed.decodeOrigin(json))

        val explicitOrigin = PipelineValue(
            value = 42,
            serializer = Int.serializer() as kotlinx.serialization.KSerializer<Any?>,
            originSerializer = Int.serializer() as kotlinx.serialization.KSerializer<Any?>,
        )
        assertNull(explicitOrigin.decodeOrigin(json))
    }

    @Test
    fun `asString covers json scalar null and typed values`() {
        assertEquals("text", PipelineValue.ofJson(JsonPrimitive("text")).asString)
        assertNull(PipelineValue.ofJson(JsonNull).asString)
        assertEquals("42", PipelineValue.of(42, Int.serializer()).asString)
        assertEquals("null", PipelineValue.of<String?>(null, String.serializer().nullable).asString)
    }

    @Test
    fun `reified value factory uses the compiled serializer`() {
        val value = PipelineValue.of(42)
        assertEquals(42, value.value)
        assertEquals(Int.serializer().descriptor.serialName, value.typeName)
    }
}

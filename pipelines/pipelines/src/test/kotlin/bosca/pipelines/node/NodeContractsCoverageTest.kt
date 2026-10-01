package bosca.pipelines.node

import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Targeted branch coverage for the node-contract types in `core-pipelines/node` that the broader
 * happy-path suites leave with partially-taken branches: the residual short-circuit arms of
 * [JsonSchemaValidator]'s numeric `matchesType` / `required` extraction, the partial default-args
 * masks of [InputNode] / [OutputNode] / [NodePosition], [NodeInputs.require], and
 * [PipelineValue.toJson] / [PipelineValue.decodeOrigin] / [PipelineValue.entityReference] arms.
 */
class NodeContractsCoverageTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val json = Json

    private fun parse(s: String): JsonElement = Json.parseToJsonElement(s)

    // =====================================================================================
    // JsonSchemaValidator — the remaining short-circuit arms
    // =====================================================================================

    @Test
    fun `a required entry that is not a primitive is dropped, not crashed`() {
        // L45 `(it as? JsonPrimitive)?.content` null arm: a nested object in the required array is
        // filtered out by mapNotNull, leaving only the string entry to enforce.
        val schema = parse("""{"type":"object","required":["a",{"nested":1}]}""")
        // 'a' is present so the only real requirement holds; the object entry was dropped.
        assertEquals(emptyList(), JsonSchemaValidator.validate(parse("""{"a":1}"""), schema))
        // and when 'a' is missing the string requirement still fires (proving the list wasn't empty).
        val bad = JsonSchemaValidator.validate(parse("""{"b":2}"""), schema)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("missing required field 'a'"), bad.single())
    }

    @Test
    fun `type number rejects a non-primitive object - the first conjunct fails`() {
        // L65 `value is JsonPrimitive` false arm.
        val bad = JsonSchemaValidator.validate(parse("{}"), parse("""{"type":"number"}"""))
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected number"), bad.single())
    }

    @Test
    fun `type number rejects a non-numeric unquoted literal - the last conjunct fails`() {
        // L65 `toDoubleOrNull() != null` false arm: primitive, non-string, not parseable as a double.
        val weird = JsonUnquotedLiteral("0x1f")
        val bad = JsonSchemaValidator.validate(weird, parse("""{"type":"number"}"""))
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected number"), bad.single())
    }

    @Test
    fun `type integer rejects a non-primitive array - the first conjunct fails`() {
        // L66 `value is JsonPrimitive` false arm.
        val bad = JsonSchemaValidator.validate(parse("[]"), parse("""{"type":"integer"}"""))
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected integer"), bad.single())
    }

    @Test
    fun `type integer rejects a fractional literal - the last conjunct fails`() {
        // L66 `toLongOrNull() != null` false arm: a primitive, non-string, but not a Long.
        val bad = JsonSchemaValidator.validate(JsonUnquotedLiteral("1.5"), parse("""{"type":"integer"}"""))
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected integer"), bad.single())
    }

    @Test
    fun `type boolean rejects a non-primitive object - the first conjunct fails`() {
        // L67 `value is JsonPrimitive` false arm.
        val bad = JsonSchemaValidator.validate(parse("{}"), parse("""{"type":"boolean"}"""))
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected boolean"), bad.single())
    }

    @Test
    fun `type boolean rejects a non-boolean literal - the last conjunct fails`() {
        // L67 `toBooleanStrictOrNull() != null` false arm: a numeric literal isn't a strict boolean.
        val bad = JsonSchemaValidator.validate(JsonUnquotedLiteral("1"), parse("""{"type":"boolean"}"""))
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected boolean"), bad.single())
    }

    @Test
    fun `primitiveKind names an integer and a number in a type-mismatch message`() {
        // L82 `toLongOrNull() != null -> integer` and L83 `toDoubleOrNull() != null -> number`,
        // reached via describe on a non-string primitive when the declared type doesn't match.
        val asInteger = JsonSchemaValidator.validate(JsonPrimitive(7), parse("""{"type":"string"}"""))
        assertTrue(asInteger.single().contains("got a integer"), asInteger.single())
        val asNumber = JsonSchemaValidator.validate(JsonPrimitive(7.5), parse("""{"type":"string"}"""))
        assertTrue(asNumber.single().contains("got a number"), asNumber.single())
    }

    @Test
    fun `primitiveKind falls back to primitive for a non-numeric literal`() {
        // L82/L83 both false arm -> the else 'primitive' branch of primitiveKind.
        val bad = JsonSchemaValidator.validate(JsonUnquotedLiteral("0x1f"), parse("""{"type":"string"}"""))
        assertTrue(bad.single().contains("got a primitive"), bad.single())
    }

    // =====================================================================================
    // InputNode / OutputNode — partial default-args masks (L22 / L59 annotation-header branches)
    // =====================================================================================

    @Test
    fun `InputNode constructed with only some optional args leaves the rest default`() {
        // Partial subset: id + name + acceptedType provided; description, schema, position default.
        val node = InputNode(id = "n1", name = "Start", acceptedType = "io.bosca.SomeEvent")
        assertEquals("n1", node.id)
        assertEquals("Start", node.name)
        assertEquals("", node.description)
        assertEquals("io.bosca.SomeEvent", node.acceptedType)
        assertNull(node.schema)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `InputNode constructed with a schema but default name and position`() {
        // A different partial subset of the optional-arg mask.
        val schema = buildJsonObject { put("type", "object") }
        val node = InputNode(id = "n2", acceptedType = InputNode.JSON_TYPE, schema = schema)
        assertEquals("n2", node.id)
        assertEquals("", node.name)
        assertEquals(schema, node.schema)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `OutputNode constructed with only some optional args leaves the rest default`() {
        // Partial subset: id + outputType provided; name, description, schema, position default.
        val node = OutputNode(id = "o1", outputType = "JSON")
        assertEquals("o1", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals("JSON", node.outputType)
        assertNull(node.schema)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `OutputNode constructed with a position but default outputType`() {
        val node = OutputNode(id = "o2", position = NodePosition(x = 9.0))
        assertEquals("o2", node.id)
        assertEquals("", node.outputType)
        assertEquals(9.0, node.position.x)
        assertEquals(0.0, node.position.y)
    }

    // =====================================================================================
    // NodePosition — partial default-args mask (L6)
    // =====================================================================================

    @Test
    fun `NodePosition built with only x leaves y default and reads both getters`() {
        val pos = NodePosition(x = 5.0)
        assertEquals(5.0, pos.x)
        assertEquals(0.0, pos.y)

        val posY = NodePosition(y = 3.0)
        assertEquals(0.0, posY.x)
        assertEquals(3.0, posY.y)

        val both = NodePosition()
        assertEquals(0.0, both.x)
        assertEquals(0.0, both.y)
    }

    // =====================================================================================
    // NodeInputs.require (L19)
    // =====================================================================================

    @Test
    fun `require returns the value on a present port`() {
        val value = PipelineValue.of(1, Int.serializer())
        val inputs = NodeInputs(mapOf("in" to value))
        assertSame(value, inputs.require("in"))
    }

    @Test
    fun `require errors naming the missing port`() {
        val inputs = NodeInputs(emptyMap())
        val ex = assertFailsWith<IllegalStateException> { inputs.require("missing") }
        assertTrue(ex.message!!.contains("'missing'"), ex.message)
    }

    // =====================================================================================
    // PipelineValue — toJson (L60) and decodeOrigin (L69) arms
    // =====================================================================================

    @Test
    fun `toJson encodes a typed value retaining the serializer as origin`() {
        // L60: non-JSON value -> a fresh JSON-form value whose origin is the original serializer.
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val asJson = typed.toJson(json)
        assertTrue(asJson.value is JsonElement)
        assertEquals(Person.serializer().descriptor.serialName, asJson.typeName)
    }

    @Test
    fun `toJson is a no-op for an already-json value`() {
        val plain = PipelineValue.ofJson(buildJsonObject { put("x", 1) })
        assertSame(plain, plain.toJson(json))
    }

    @Test
    fun `decodeOrigin reconstructs the original type from the json form`() {
        // L69 success arm: origin present AND value is a JsonElement.
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val back = typed.toJson(json).decodeOrigin(json)
        assertNotNull(back)
        assertEquals(Person("Ada", "ada@x.io"), back.value)
    }

    @Test
    fun `decodeOrigin returns null without an origin serializer`() {
        // L68 elvis-null arm: a plain-JSON value carries no origin.
        val plain = PipelineValue.ofJson(buildJsonObject { put("x", 1) })
        assertNull(plain.decodeOrigin(json))
    }

    // =====================================================================================
    // PipelineValue.decode — the generic slot decode the generated <Node>Serializers call
    // =====================================================================================

    @Test
    fun `decode bridges an already-typed value through its JSON form`() {
        val typed = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        assertEquals(Person("Ada", "ada@x.io"), typed.decode(Person.serializer(), json))
    }

    @Test
    fun `decode accepts plain JSON that satisfies the target shape`() {
        // A JSONata-style reshape carries no typed origin, but its shape still decodes.
        val plain = PipelineValue.ofJson(parse("""{"name":"Ada","email":"ada@x.io"}"""))
        assertEquals(Person("Ada", "ada@x.io"), plain.decode(Person.serializer(), json))
    }

    @Test
    fun `decode returns null for JSON null`() {
        assertNull(PipelineValue.ofJson(JsonNull).decode(Person.serializer(), json))
    }

    @Test
    fun `decode fails on a value that does not match the target shape`() {
        val plain = PipelineValue.ofJson(parse("""{"unrelated":true}"""))
        assertFailsWith<kotlinx.serialization.SerializationException> {
            plain.decode(Person.serializer(), json)
        }
    }

    // =====================================================================================
    // EntityReference (L35) — the field.let extraction arm, valid and parse-failure
    // =====================================================================================

    @Test
    fun `entityReference parses a valid id field and skips a non-parseable one`() {
        // L35 success: a valid UUID string in the first candidate is parsed.
        val id = UUID.random()
        val valid = PipelineValue.ofJson(buildJsonObject { put("id", id.toString()) })
        assertEquals(id, valid.entityReference(json)?.id)

        // L35 catch arm: a present-but-invalid candidate returns null from the let, falling through.
        val invalid = PipelineValue.ofJson(buildJsonObject { put("id", "not-a-uuid") })
        assertNull(invalid.entityReference(json))
    }

    @Test
    fun `entityReference reads a version when present and null when absent`() {
        val id = UUID.random()
        val withVersion = PipelineValue.ofJson(buildJsonObject {
            put("id", id.toString())
            put("version", 4)
        })
        val ref = withVersion.entityReference(json)
        assertNotNull(ref)
        assertEquals(id, ref.id)
        assertEquals(4, ref.version)

        val noVersion = PipelineValue.ofJson(buildJsonObject { put("id", id.toString()) })
        assertNull(noVersion.entityReference(json)?.version)
    }

    @Test
    fun `entityReference returns null when the encoded value is not an object`() {
        val arr = PipelineValue.ofJson(buildJsonArray { add(JsonPrimitive(1)) })
        assertNull(arr.entityReference(json))

        val nul = PipelineValue.ofJson(JsonNull)
        assertNull(nul.entityReference(json))
    }

    @Test
    fun `entityReference skips an id field that is present but not a primitive`() {
        // L35 `(obj[field] as? JsonPrimitive)` false arm with a PRESENT non-primitive value: the id
        // field holds a nested object, so the cast yields null and the candidate is dropped.
        val value = PipelineValue.ofJson(buildJsonObject {
            put("id", buildJsonObject { put("nested", 1) })
        })
        assertNull(value.entityReference(json))
    }

    @Test
    fun `entityReference falls through a non-primitive first candidate to a valid later one`() {
        // The first candidate is a present non-primitive (dropped), the second is a valid UUID string,
        // so firstNotNullOfOrNull advances past the null and returns the parsed reference.
        val id = UUID.random()
        val value = PipelineValue.ofJson(buildJsonObject {
            put("taskId", buildJsonArray { add(JsonPrimitive("x")) })
            put("id", id.toString())
        })
        val ref = value.entityReference(json, idFields = listOf("taskId", "id"))
        assertNotNull(ref)
        assertEquals(id, ref.id)
    }

    // =====================================================================================
    // NodePosition — equals / hashCode arms (L6 synthetic data-class methods)
    // =====================================================================================

    @Test
    fun `NodePosition equals covers identity, equal, per-field difference and a foreign type`() {
        val p = NodePosition(1.0, 2.0)
        @Suppress("KotlinConstantConditions")
        assertTrue(p == p)                                     // reference-equal arm
        assertEquals(p, NodePosition(1.0, 2.0))               // structurally equal arm
        assertEquals(p.hashCode(), NodePosition(1.0, 2.0).hashCode())
        assertNotEquals(p, NodePosition(9.0, 2.0))            // x differs
        assertNotEquals(p, NodePosition(1.0, 9.0))            // y differs
        assertNotEquals<Any?>(p, "not a position")            // instanceof false arm
        assertNotEquals<Any?>(p, null)                        // null arm
        assertTrue(p.toString().contains("1.0"))
        assertEquals(1.0, p.component1())
        assertEquals(2.0, p.component2())
        assertEquals(NodePosition(5.0, 2.0), p.copy(x = 5.0))
    }

    // =====================================================================================
    // InputNode / OutputNode — additional single-omission default-arg masks (L22 / L59)
    // =====================================================================================

    @Test
    fun `InputNode constructed omitting each optional arg in turn flips distinct mask bits`() {
        val schema = buildJsonObject { put("type", "object") }
        val pos = NodePosition(1.0, 2.0)
        // omit only name
        val noName = InputNode(id = "n", acceptedType = "T", description = "d", schema = schema, position = pos)
        assertEquals("", noName.name)
        // omit only description
        val noDesc = InputNode(id = "n", name = "x", acceptedType = "T", schema = schema, position = pos)
        assertEquals("", noDesc.description)
        // omit only schema
        val noSchema = InputNode(id = "n", name = "x", description = "d", acceptedType = "T", position = pos)
        assertNull(noSchema.schema)
        // omit only position
        val noPos = InputNode(id = "n", name = "x", description = "d", acceptedType = "T", schema = schema)
        assertEquals(NodePosition(), noPos.position)
    }

    @Test
    fun `OutputNode constructed omitting each optional arg in turn flips distinct mask bits`() {
        val schema = buildJsonObject { put("type", "string") }
        val pos = NodePosition(3.0, 4.0)
        // omit only name
        val noName = OutputNode(id = "o", description = "d", outputType = "JSON", schema = schema, position = pos)
        assertEquals("", noName.name)
        // omit only description
        val noDesc = OutputNode(id = "o", name = "x", outputType = "JSON", schema = schema, position = pos)
        assertEquals("", noDesc.description)
        // omit only outputType
        val noType = OutputNode(id = "o", name = "x", description = "d", schema = schema, position = pos)
        assertEquals("", noType.outputType)
        // omit only schema
        val noSchema = OutputNode(id = "o", name = "x", description = "d", outputType = "JSON", position = pos)
        assertNull(noSchema.schema)
        // omit only position
        val noPos = OutputNode(id = "o", name = "x", description = "d", outputType = "JSON", schema = schema)
        assertEquals(NodePosition(), noPos.position)
    }

    // =====================================================================================
    // PipelineResumeCorrelation — equals per-field difference arms (L17 data-class methods)
    // =====================================================================================

    @Test
    fun `PipelineResumeCorrelation equals covers each field difference and a foreign type`() {
        val id = UUID.random()
        val corr = PipelineResumeCorrelation(runId = id, nodeId = "n")
        assertEquals(corr, corr.copy())
        assertEquals(corr.hashCode(), corr.copy().hashCode())
        assertNotEquals(corr, corr.copy(runId = UUID.random()))   // runId differs
        assertNotEquals(corr, corr.copy(nodeId = "m"))            // nodeId differs
        assertNotEquals<Any?>(corr, "not a correlation")          // instanceof false arm
        assertEquals(id, corr.runId)
        assertEquals("n", corr.nodeId)
        assertTrue(corr.toString().contains("n"))
    }
}

package bosca.pipelines.node

import bosca.pipelines.annotation.SlotKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotValidatorTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val json = Json

    private fun slot(
        name: String = "value",
        kind: SlotKind = SlotKind.ANY,
        type: String? = null,
        schema: String? = null,
        required: Boolean = true,
    ) = NodeInputSlot(
        name = name,
        typeLabel = kind.name,
        kind = kind,
        type = type,
        schema = schema?.let { Json.parseToJsonElement(it) },
        required = required,
    )

    @Test
    fun `ANY kind accepts anything`() {
        val value = PipelineValue.of(42, Int.serializer())
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.ANY), value, json))
    }

    @Test
    fun `INTEGER accepts an int and rejects a string`() {
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.INTEGER), PipelineValue.of(7, Int.serializer()), json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.INTEGER), PipelineValue.of("seven", String.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected integer"), bad.single())
    }

    @Test
    fun `UUID accepts a canonical uuid string and rejects a non-uuid string`() {
        val ok = PipelineValue.of("3f2504e0-4f89-41d3-9a0c-0305e82c3301", String.serializer())
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.UUID), ok, json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.UUID), PipelineValue.of("not-a-uuid", String.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected uuid"), bad.single())
    }

    @Test
    fun `OBJECT accepts a typed object encoded to JSON and rejects a scalar`() {
        val person = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.OBJECT), person, json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.OBJECT), PipelineValue.of(1, Int.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected object"), bad.single())
    }

    @Test
    fun `specific type constraint matches the carried serial name and rejects another`() {
        val person = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val typeName = Person.serializer().descriptor.serialName
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.OBJECT, type = typeName), person, json))
        val wrong = SlotValidator.validate(slot(kind = SlotKind.OBJECT, type = "some.other.Type"), person, json)
        assertEquals(1, wrong.size)
        assertTrue(wrong.single().contains("some.other.Type"), wrong.single())
    }

    @Test
    fun `an ARRAY slot's type constrains the ELEMENTS of a typed list, not the container`() {
        // The release-relay failure: List<ArtifactPublication> into an ARRAY slot typed to the element
        // class was rejected because the list's own serial name is "kotlin.collections.ArrayList".
        val typeName = Person.serializer().descriptor.serialName
        val people = PipelineValue.of(
            listOf(Person("Ada", "ada@x.io")),
            kotlinx.serialization.builtins.ListSerializer(Person.serializer()),
        )
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.ARRAY, type = typeName), people, json))
        val wrong = SlotValidator.validate(slot(kind = SlotKind.ARRAY, type = "some.other.Type"), people, json)
        assertEquals(1, wrong.size)
        assertTrue(wrong.single().contains("expected items of type 'some.other.Type'"), wrong.single())
    }

    @Test
    fun `an ARRAY slot reads its element type from a JSON value's carried origin serializer`() {
        val typeName = Person.serializer().descriptor.serialName
        val people = PipelineValue.of(
            listOf(Person("Ada", "ada@x.io")),
            kotlinx.serialization.builtins.ListSerializer(Person.serializer()),
        ).toJson(json)

        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.ARRAY, type = typeName), people, json))
    }

    @Test
    fun `an ARRAY slot compares a typed non-list value by its own serial name`() {
        val person = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val violations = SlotValidator.validate(slot(kind = SlotKind.ARRAY, type = "some.other.Type"), person, json)

        assertTrue(violations.any { it.contains("expected items of type") })
    }

    @Test
    fun `an ARRAY slot's type constraint is skipped for a plain JSON array`() {
        val plain = PipelineValue.ofJson(buildJsonArray { add(buildJsonObject { put("email", "ada@x.io") }) })
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.ARRAY, type = "io.bosca.Profile"), plain, json))
    }

    @Test
    fun `specific type constraint is skipped for plain JSON with no typed origin`() {
        // A JSONata-style value: plain JSON, no carried type — the FQDN check can't disprove it.
        val plain = PipelineValue.ofJson(buildJsonObject { put("email", "ada@x.io") })
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.OBJECT, type = "io.bosca.Profile"), plain, json))
    }

    @Test
    fun `schema is enforced even on plain JSON, the JSONata-to-unknown case`() {
        val schema = """{"type":"object","required":["email"],"properties":{"email":{"type":"string"}}}"""
        val ok = PipelineValue.ofJson(buildJsonObject { put("email", "ada@x.io") })
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.OBJECT, schema = schema), ok, json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.OBJECT, schema = schema), PipelineValue.ofJson(buildJsonObject { put("name", "Ada") }), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("missing required field 'email'"), bad.single())
    }

    @Test
    fun `a single-slot node matches its sole input regardless of port name`() {
        val slots = listOf(slot(name = "count", kind = SlotKind.INTEGER))
        val inputs = NodeInputs(mapOf("someUpstreamId" to PipelineValue.of(3, Int.serializer())))
        assertEquals(emptyList(), SlotValidator.validate(slots, inputs, json))
    }

    @Test
    fun `a multi-slot node matches each value by port name`() {
        val slots = listOf(
            slot(name = "id", kind = SlotKind.UUID),
            slot(name = "count", kind = SlotKind.INTEGER),
        )
        val inputs = NodeInputs(
            mapOf(
                "id" to PipelineValue.of("3f2504e0-4f89-41d3-9a0c-0305e82c3301", String.serializer()),
                "count" to PipelineValue.of("nope", String.serializer()),
            )
        )
        val violations = SlotValidator.validate(slots, inputs, json)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("input 'count'"), violations.single())
    }

    @Test
    fun `a required slot with no value is a violation, an optional one is not`() {
        val required = SlotValidator.validate(listOf(slot(name = "count", kind = SlotKind.INTEGER, required = true)), NodeInputs(emptyMap()), json)
        assertEquals(1, required.size)
        assertTrue(required.single().contains("required"), required.single())

        val optional = SlotValidator.validate(listOf(slot(name = "count", kind = SlotKind.INTEGER, required = false)), NodeInputs(emptyMap()), json)
        assertEquals(emptyList(), optional)
    }

    // ---- added coverage: empty-slots short circuit ----

    @Test
    fun `no slots means no violations regardless of inputs`() {
        val inputs = NodeInputs(mapOf("x" to PipelineValue.of(1, Int.serializer())))
        assertEquals(emptyList(), SlotValidator.validate(emptyList(), inputs, json))
    }

    // ---- added coverage: every SlotKind happy + failure path ----

    @Test
    fun `STRING accepts a string and rejects a non-string`() {
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.STRING), PipelineValue.of("hi", String.serializer()), json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.STRING), PipelineValue.of(1, Int.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected string"), bad.single())
    }

    @Test
    fun `NUMBER accepts a double and rejects a string`() {
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.NUMBER), PipelineValue.of(1.5, Double.serializer()), json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.NUMBER), PipelineValue.of("1.5", String.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected number"), bad.single())
    }

    @Test
    fun `BOOLEAN accepts a boolean and rejects a string`() {
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.BOOLEAN), PipelineValue.of(true, Boolean.serializer()), json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.BOOLEAN), PipelineValue.of("true", String.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected boolean"), bad.single())
    }

    @Test
    fun `ARRAY accepts a list and rejects a scalar`() {
        val list = PipelineValue.ofJson(buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) })
        assertEquals(emptyList(), SlotValidator.validate(slot(kind = SlotKind.ARRAY), list, json))
        val bad = SlotValidator.validate(slot(kind = SlotKind.ARRAY), PipelineValue.of(1, Int.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected array"), bad.single())
    }

    // ---- added coverage: every describe() arm via a failing kind ----

    @Test
    fun `describe names an object when a non-object value fails the object kind`() {
        // STRING kind fed an object -> kindViolation runs describe over a JsonObject.
        val obj = PipelineValue.ofJson(buildJsonObject { put("a", 1) })
        val bad = SlotValidator.validate(slot(kind = SlotKind.STRING), obj, json)
        assertTrue(bad.single().contains("got an object"), bad.single())
    }

    @Test
    fun `describe names an array`() {
        val arr = PipelineValue.ofJson(buildJsonArray { add(JsonPrimitive(1)) })
        val bad = SlotValidator.validate(slot(kind = SlotKind.STRING), arr, json)
        assertTrue(bad.single().contains("got an array"), bad.single())
    }

    @Test
    fun `describe names null`() {
        val nul = PipelineValue.ofJson(JsonNull)
        val bad = SlotValidator.validate(slot(kind = SlotKind.STRING), nul, json)
        assertTrue(bad.single().contains("got null"), bad.single())
    }

    @Test
    fun `describe names a string`() {
        // OBJECT kind fed a string -> describe over a string primitive.
        val bad = SlotValidator.validate(slot(kind = SlotKind.OBJECT), PipelineValue.of("hi", String.serializer()), json)
        assertTrue(bad.single().contains("got a string"), bad.single())
    }

    @Test
    fun `describe names a boolean`() {
        val bad = SlotValidator.validate(slot(kind = SlotKind.OBJECT), PipelineValue.of(true, Boolean.serializer()), json)
        assertTrue(bad.single().contains("got a boolean"), bad.single())
    }

    @Test
    fun `describe names an integer`() {
        val bad = SlotValidator.validate(slot(kind = SlotKind.OBJECT), PipelineValue.of(42, Int.serializer()), json)
        assertTrue(bad.single().contains("got an integer"), bad.single())
    }

    @Test
    fun `describe names a number`() {
        val bad = SlotValidator.validate(slot(kind = SlotKind.OBJECT), PipelineValue.of(1.5, Double.serializer()), json)
        assertTrue(bad.single().contains("got a number"), bad.single())
    }

    @Test
    fun `describe falls back to a primitive for a non-numeric unquoted literal`() {
        // An unquoted literal that is not a string, boolean, integer or double -> the else arm.
        val weird = PipelineValue.ofJson(JsonUnquotedLiteral("0x1f"))
        val bad = SlotValidator.validate(slot(kind = SlotKind.OBJECT), weird, json)
        assertTrue(bad.single().contains("got a primitive"), bad.single())
    }

    // ---- added coverage: kind + type + schema all violate on one slot ----

    @Test
    fun `kind type and schema violations all accumulate for one slot`() {
        // A typed Person scalar mismatch: feed an INTEGER (wrong kind), demand an object type, and a schema.
        val schema = """{"type":"object","required":["email"],"properties":{"email":{"type":"string"}}}"""
        val s = slot(kind = SlotKind.OBJECT, type = "io.bosca.Profile", schema = schema)
        // A typed value whose typeName is a serial name that won't equal the demanded type, and is a scalar.
        val value = PipelineValue.of(7, Int.serializer())
        val violations = SlotValidator.validate(s, value, json)
        // kind (expected object) + type (typeName 'kotlin.Int' != demanded) + schema (expected object).
        assertEquals(3, violations.size, violations.toString())
        assertTrue(violations.any { it.contains("expected object") }, violations.toString())
        assertTrue(violations.any { it.contains("io.bosca.Profile") }, violations.toString())
    }

    @Test
    fun `a slot with no constraints beyond ANY produces no violation`() {
        // type == null and schema == null arms both skipped.
        val s = slot(kind = SlotKind.ANY, type = null, schema = null)
        assertEquals(emptyList(), SlotValidator.validate(s, PipelineValue.of("anything", String.serializer()), json))
    }

    @Test
    fun `a typed value satisfies its declared type but a schema on top still applies`() {
        val schema = """{"type":"object","required":["missing"]}"""
        val person = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val typeName = Person.serializer().descriptor.serialName
        val violations = SlotValidator.validate(slot(kind = SlotKind.OBJECT, type = typeName, schema = schema), person, json)
        // kind ok, type ok, schema fails on the missing required field.
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains("missing required field 'missing'"), violations.single())
    }

    // ---- added coverage: the remaining short-circuit arms of the numeric/uuid kind checks ----

    @Test
    fun `INTEGER rejects a non-primitive object - the first conjunct fails`() {
        // L66 `value is JsonPrimitive` false arm: an object is not a primitive.
        val obj = PipelineValue.ofJson(buildJsonObject { put("a", 1) })
        val bad = SlotValidator.validate(slot(kind = SlotKind.INTEGER), obj, json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected integer"), bad.single())
    }

    @Test
    fun `INTEGER rejects a numeric-looking but non-integer literal - the last conjunct fails`() {
        // L66 `toLongOrNull() != null` false arm: a fractional unquoted literal isn't a Long.
        val frac = PipelineValue.ofJson(JsonUnquotedLiteral("1.5"))
        val bad = SlotValidator.validate(slot(kind = SlotKind.INTEGER), frac, json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected integer"), bad.single())
    }

    @Test
    fun `NUMBER rejects a non-numeric unquoted literal - the last conjunct fails`() {
        // L67 `toDoubleOrNull() != null` false arm.
        val weird = PipelineValue.ofJson(JsonUnquotedLiteral("0x1f"))
        val bad = SlotValidator.validate(slot(kind = SlotKind.NUMBER), weird, json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected number"), bad.single())
    }

    @Test
    fun `BOOLEAN rejects a non-boolean unquoted literal - the last conjunct fails`() {
        // L68 `toBooleanStrictOrNull() != null` false arm: an integer literal isn't a strict boolean.
        val notBool = PipelineValue.ofJson(JsonUnquotedLiteral("1"))
        val bad = SlotValidator.validate(slot(kind = SlotKind.BOOLEAN), notBool, json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected boolean"), bad.single())
    }

    @Test
    fun `UUID rejects a non-string primitive - the isString conjunct fails`() {
        // L69 `value.isString` false arm: a numeric primitive is not a string.
        val bad = SlotValidator.validate(slot(kind = SlotKind.UUID), PipelineValue.of(42, Int.serializer()), json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected uuid"), bad.single())
    }

    // ---- added coverage: the leading `value is JsonPrimitive` conjunct failing for each kind ----

    @Test
    fun `NUMBER rejects a non-primitive object - the first conjunct fails`() {
        // L67 `value is JsonPrimitive` false arm: an object is not a primitive at all.
        val obj = PipelineValue.ofJson(buildJsonObject { put("a", 1) })
        val bad = SlotValidator.validate(slot(kind = SlotKind.NUMBER), obj, json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected number"), bad.single())
    }

    @Test
    fun `BOOLEAN rejects a non-primitive array - the first conjunct fails`() {
        // L68 `value is JsonPrimitive` false arm: an array is not a primitive.
        val arr = PipelineValue.ofJson(buildJsonArray { add(JsonPrimitive(1)) })
        val bad = SlotValidator.validate(slot(kind = SlotKind.BOOLEAN), arr, json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected boolean"), bad.single())
    }

    @Test
    fun `UUID rejects a non-primitive object - the first conjunct fails`() {
        // L69 `value is JsonPrimitive` false arm: an object is not a primitive.
        val obj = PipelineValue.ofJson(buildJsonObject { put("a", 1) })
        val bad = SlotValidator.validate(slot(kind = SlotKind.UUID), obj, json)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("expected uuid"), bad.single())
    }
}

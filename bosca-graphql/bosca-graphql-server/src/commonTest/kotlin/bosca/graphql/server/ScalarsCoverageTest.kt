@file:OptIn(ExperimentalUuidApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package bosca.graphql.server

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.EnumValue
import bosca.graphql.language.FloatValue
import bosca.graphql.language.IntValue
import bosca.graphql.language.ListValue
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectField
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.StringValue
import bosca.graphql.language.Variable
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Exhaustive branch coverage for every built-in and extended scalar coercing, plus `unwrap`/`astToJson`. */
class ScalarsCoverageTest {

    private val int = Scalars.coercings.getValue("Int")
    private val float = Scalars.coercings.getValue("Float")
    private val string = Scalars.coercings.getValue("String")
    private val boolean = Scalars.coercings.getValue("Boolean")
    private val id = Scalars.coercings.getValue("ID")

    @Test
    fun `unwrap reduces every JSON shape`() {
        assertEquals(null, unwrap(JsonNull))
        assertEquals("s", unwrap(JsonPrimitive("s")))
        assertEquals(true, unwrap(JsonPrimitive(true)))
        assertEquals(5L, unwrap(JsonPrimitive(5)))
        assertEquals(1.5, unwrap(JsonPrimitive(1.5)))
        assertEquals("raw", unwrap(JsonUnquotedLiteral("raw"))) // non-string, non-numeric, non-bool primitive
        assertEquals(42, unwrap(42)) // a non-JSON value passes through
    }

    @Test
    fun `astToJson converts every literal and rejects variables`() {
        assertEquals(JsonPrimitive(1L), astToJson(IntValue("1")))
        assertEquals(JsonPrimitive(1.5), astToJson(FloatValue("1.5")))
        assertEquals(JsonPrimitive("s"), astToJson(StringValue("s")))
        assertEquals(JsonPrimitive(true), astToJson(BooleanValue(true)))
        assertEquals(JsonNull, astToJson(NullValue()))
        assertEquals(JsonPrimitive("E"), astToJson(EnumValue("E")))
        assertEquals("[1]", astToJson(ListValue(listOf(IntValue("1")))).toString())
        assertEquals("""{"k":1}""", astToJson(ObjectValue(listOf(ObjectField("k", IntValue("1"))))).toString())
        assertFailsWith<CoercingException> { astToJson(Variable("v")) }
        // out-of-range / non-finite numeric literals become a clean coercion error, not an uncaught crash
        assertFailsWith<CoercingException> { astToJson(IntValue("99999999999999999999")) }
        assertFailsWith<CoercingException> { astToJson(FloatValue("1e400")) }
    }

    @Test
    fun `Int coercing covers every branch`() {
        assertEquals(JsonPrimitive(3), int.serialize(3))
        assertEquals(JsonPrimitive(3), int.serialize(3L)) // Long in range
        assertFailsWith<CoercingException> { int.serialize(Long.MAX_VALUE) } // overflow
        assertFailsWith<CoercingException> { int.serialize("x") } // wrong type
        assertEquals(7, int.parseValue(JsonPrimitive(7)))
        assertFailsWith<CoercingException> { int.parseValue(JsonPrimitive("x")) }
        assertEquals(9, int.parseLiteral(IntValue("9")))
        assertFailsWith<CoercingException> { int.parseLiteral(FloatValue("1.5")) }
        assertFailsWith<CoercingException> { int.parseLiteral(IntValue("99999999999999")) } // not an Int
    }

    @Test
    fun `Float coercing covers every branch`() {
        assertEquals(JsonPrimitive(1.5), float.serialize(1.5))
        assertEquals(JsonPrimitive(2.0), float.serialize(2.0f))
        assertEquals(JsonPrimitive(3.0), float.serialize(3))
        assertEquals(JsonPrimitive(4.0), float.serialize(4L))
        assertFailsWith<CoercingException> { float.serialize("x") }
        assertEquals(1.5, float.parseValue(JsonPrimitive(1.5)))
        assertFailsWith<CoercingException> { float.parseValue(JsonPrimitive("x")) }
        assertEquals(1.5, float.parseLiteral(FloatValue("1.5")))
        assertEquals(2.0, float.parseLiteral(IntValue("2")))
        assertFailsWith<CoercingException> { float.parseLiteral(StringValue("x")) }
        // §3.5.2: NaN / ±Infinity are not valid Float values, on input or output.
        assertFailsWith<CoercingException> { float.serialize(Double.NaN) }
        assertFailsWith<CoercingException> { float.serialize(Double.POSITIVE_INFINITY) }
        assertFailsWith<CoercingException> { float.serialize(Float.NEGATIVE_INFINITY) }
        assertFailsWith<CoercingException> { float.parseValue(Double.NaN) }
        assertFailsWith<CoercingException> { float.parseLiteral(FloatValue("1e400")) } // overflows to Infinity
    }

    @Test
    fun `String coercing covers every branch`() {
        assertEquals(JsonPrimitive("5"), string.serialize(5))
        assertFailsWith<CoercingException> { string.serialize(null) }
        assertEquals("s", string.parseValue(JsonPrimitive("s")))
        assertFailsWith<CoercingException> { string.parseValue(JsonPrimitive(1)) }
        assertEquals("s", string.parseLiteral(StringValue("s")))
        assertFailsWith<CoercingException> { string.parseLiteral(IntValue("1")) }
    }

    @Test
    fun `Boolean coercing covers every branch`() {
        assertEquals(JsonPrimitive(true), boolean.serialize(true))
        assertFailsWith<CoercingException> { boolean.serialize("x") }
        assertEquals(false, boolean.parseValue(JsonPrimitive(false)))
        assertFailsWith<CoercingException> { boolean.parseValue(JsonPrimitive("x")) }
        assertEquals(true, boolean.parseLiteral(BooleanValue(true)))
        assertFailsWith<CoercingException> { boolean.parseLiteral(IntValue("1")) }
    }

    @Test
    fun `ID coercing covers every branch`() {
        assertEquals(JsonPrimitive("x"), id.serialize("x"))
        assertEquals(JsonPrimitive("7"), id.serialize(7))
        assertEquals(JsonPrimitive("8"), id.serialize(8L))
        assertFailsWith<CoercingException> { id.serialize(1.5) }
        assertEquals("x", id.parseValue(JsonPrimitive("x")))
        assertEquals("7", id.parseValue(JsonPrimitive(7))) // a JSON int becomes a Long → string
        assertFailsWith<CoercingException> { id.parseValue(JsonPrimitive(1.5)) }
        assertEquals("x", id.parseLiteral(StringValue("x")))
        assertEquals("7", id.parseLiteral(IntValue("7")))
        assertFailsWith<CoercingException> { id.parseLiteral(FloatValue("1.5")) }
    }

    @Test
    fun `ID parseValue handles a plain Int`() {
        assertEquals("7", id.parseValue(7)) // a non-JSON Int passes through unwrap
    }

    @Test
    fun `Long coercing covers every branch`() {
        val long = ExtendedScalars.Long
        assertEquals(JsonPrimitive(5L), long.serialize(5L))
        assertEquals(JsonPrimitive(6L), long.serialize(6))
        assertFailsWith<CoercingException> { long.serialize("x") }
        assertEquals(5L, long.parseValue(JsonPrimitive(5)))
        assertFailsWith<CoercingException> { long.parseValue(JsonPrimitive("x")) }
        assertEquals(9L, long.parseLiteral(IntValue("9")))
        assertFailsWith<CoercingException> { long.parseLiteral(StringValue("x")) }
        assertFailsWith<CoercingException> { long.parseLiteral(IntValue("99999999999999999999")) } // out of Long range
    }

    @Test
    fun `UUID coercing covers every branch`() {
        val uuid = ExtendedScalars.Uuid
        val value = Uuid.parse("12345678-1234-1234-1234-123456789abc")
        assertEquals(JsonPrimitive(value.toString()), uuid.serialize(value))
        assertEquals(JsonPrimitive(value.toString()), uuid.serialize(value.toString())) // String input
        assertFailsWith<CoercingException> { uuid.serialize(1) }
        assertEquals(value, uuid.parseValue(value))
        assertEquals(value, uuid.parseValue(value.toString()))
        assertFailsWith<CoercingException> { uuid.parseValue(1) }
        assertEquals(value, uuid.parseLiteral(StringValue(value.toString())))
        assertFailsWith<CoercingException> { uuid.parseLiteral(IntValue("1")) }
        assertFailsWith<CoercingException> { uuid.parseValue("not-a-uuid") } // invalid → parse throws
    }

    @Test
    fun `DateTime coercing covers every branch`() {
        val dt = ExtendedScalars.DateTime
        val instant = Instant.parse("2020-01-01T00:00:00Z")
        assertEquals(JsonPrimitive(instant.toString()), dt.serialize(instant))
        assertEquals(JsonPrimitive(instant.toString()), dt.serialize(instant.toString())) // String input
        assertFailsWith<CoercingException> { dt.serialize(1) }
        assertEquals(instant, dt.parseValue(instant))
        assertEquals(instant, dt.parseValue(instant.toString()))
        assertFailsWith<CoercingException> { dt.parseValue(1) }
        assertEquals(instant, dt.parseLiteral(StringValue(instant.toString())))
        assertFailsWith<CoercingException> { dt.parseLiteral(IntValue("1")) }
        assertFailsWith<CoercingException> { dt.parseValue("not-a-date") } // invalid → parse throws
    }

    @Test
    fun `JSON coercing covers every branch`() {
        val json = ExtendedScalars.Json
        assertEquals(JsonNull, json.serialize(null))
        val obj = buildJsonObject { put("k", 1) }
        assertEquals(obj, json.serialize(obj)) // already a JsonElement
        assertEquals(JsonPrimitive("s"), json.serialize("s"))
        assertEquals(JsonPrimitive(true), json.serialize(true))
        assertEquals(JsonPrimitive(1), json.serialize(1))
        assertEquals(JsonPrimitive(2L), json.serialize(2L))
        assertEquals(JsonPrimitive(1.5), json.serialize(1.5))
        val legacyMap = mapOf(
            "type" to "Study",
            "items" to listOf(1, null, mapOf("visible" to true)),
        )
        val legacyJson = JsonObject(
            mapOf(
                "type" to JsonPrimitive("Study"),
                "items" to JsonArray(
                    listOf(
                        JsonPrimitive(1),
                        JsonNull,
                        JsonObject(mapOf("visible" to JsonPrimitive(true))),
                    ),
                ),
            ),
        )
        assertEquals(legacyJson, json.serialize(legacyMap))
        assertEquals(legacyJson, json.parseValue(legacyMap))
        assertFailsWith<CoercingException> { json.serialize(mapOf(1 to "invalid key")) }
        assertFailsWith<CoercingException> { json.serialize(Pair(1, 2)) } // unrepresentable
        assertEquals(obj, json.parseValue(obj)) // JsonElement input passes through
        assertEquals(JsonPrimitive("s"), json.parseValue(JsonPrimitive("s")))
        assertEquals(JsonPrimitive("e"), json.parseLiteral(EnumValue("e")))
    }

    @Test
    fun `Upload coercing is input-only`() {
        val upload = ExtendedScalars.Upload
        assertFailsWith<CoercingException> { upload.serialize("x") }
        val file = Any()
        assertTrue(upload.parseValue(file) === file)
        assertFailsWith<CoercingException> { upload.parseValue(null) }
        assertFailsWith<CoercingException> { upload.parseLiteral(StringValue("x")) }
    }
}

@file:OptIn(ExperimentalUuidApi::class)

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * round-trips each scalar (literal → internal → serialized) and asserts invalid inputs raise
 * [CoercingException].
 */
class ScalarsTest {

    private fun int() = Scalars.coercings.getValue("Int")
    private fun id() = Scalars.coercings.getValue("ID")

    @Test
    fun `Int round-trips and rejects non-integers`() {
        val c = int()
        assertEquals(5, c.parseLiteral(IntValue("5")))
        assertEquals(5, c.parseValue(JsonPrimitive(5)))
        assertEquals(5, c.parseValue(5))
        assertEquals(5, c.parseValue(5L)) // a Long in Int range
        assertEquals(JsonPrimitive(5), c.serialize(5))
        assertFailsWith<CoercingException> { c.parseLiteral(FloatValue("1.5")) }
        assertFailsWith<CoercingException> { c.parseValue(JsonPrimitive("x")) }
        assertFailsWith<CoercingException> { c.serialize("x") }
        assertFailsWith<CoercingException> { c.parseValue(Long.MAX_VALUE) } // overflow rejected
    }

    @Test
    fun `Float accepts ints and floats`() {
        val c = Scalars.coercings.getValue("Float")
        assertEquals(1.5, c.parseLiteral(FloatValue("1.5")))
        assertEquals(2.0, c.parseLiteral(IntValue("2")))
        assertEquals(3.0, c.parseValue(JsonPrimitive(3)))
        assertEquals(4.0, c.parseValue(4L)) // Long → Double
        assertEquals(1.5, c.parseValue(JsonPrimitive(1.5))) // JSON double via unwrap
        assertEquals(JsonPrimitive(1.5), c.serialize(1.5))
        assertEquals(JsonPrimitive(2.5), c.serialize(2.5f)) // Float → Double
        assertFailsWith<CoercingException> { c.parseValue(JsonPrimitive("x")) }
    }

    @Test
    fun `String round-trips`() {
        val c = Scalars.coercings.getValue("String")
        assertEquals("hi", c.parseLiteral(StringValue("hi")))
        assertEquals("hi", c.parseValue(JsonPrimitive("hi")))
        assertEquals(JsonPrimitive("hi"), c.serialize("hi"))
        assertFailsWith<CoercingException> { c.parseValue(JsonPrimitive(1)) }
        assertFailsWith<CoercingException> { c.parseValue(JsonNull) } // unwrap → null
        assertFailsWith<CoercingException> { c.serialize(null) }
        assertFailsWith<CoercingException> { c.parseLiteral(IntValue("1")) }
    }

    @Test
    fun `Boolean round-trips`() {
        val c = Scalars.coercings.getValue("Boolean")
        assertEquals(true, c.parseLiteral(BooleanValue(true)))
        assertEquals(false, c.parseValue(JsonPrimitive(false)))
        assertEquals(JsonPrimitive(true), c.serialize(true))
        assertFailsWith<CoercingException> { c.serialize("x") }
        assertFailsWith<CoercingException> { c.parseLiteral(IntValue("1")) }
    }

    @Test
    fun `ID accepts strings and ints`() {
        val c = id()
        assertEquals("7", c.parseLiteral(IntValue("7")))
        assertEquals("abc", c.parseLiteral(StringValue("abc")))
        assertEquals("9", c.parseValue(9))
        assertEquals(JsonPrimitive("9"), c.serialize(9))
        assertEquals(JsonPrimitive("abc"), c.serialize("abc"))
        assertFailsWith<CoercingException> { c.serialize(1.5) }
        assertFailsWith<CoercingException> { c.parseValue(JsonPrimitive(true)) }
        assertFailsWith<CoercingException> { c.parseLiteral(FloatValue("1.5")) }
    }

    @Test
    fun `Long round-trips`() {
        val c = ExtendedScalars.Long
        assertEquals(5L, c.parseLiteral(IntValue("5")))
        assertEquals(5L, c.parseValue(JsonPrimitive(5)))
        assertEquals(5L, c.parseValue(5))
        assertEquals(5L, c.parseValue(5L))
        assertEquals(JsonPrimitive(5L), c.serialize(5L))
        assertEquals(JsonPrimitive(6L), c.serialize(6)) // Int → Long
        assertFailsWith<CoercingException> { c.serialize("x") }
        assertFailsWith<CoercingException> { c.parseValue(JsonPrimitive("x")) }
    }

    @Test
    fun `UUID round-trips and rejects invalid strings`() {
        val c = ExtendedScalars.Uuid
        val text = "00000000-0000-0000-0000-000000000001"
        val uuid = Uuid.parse(text)
        assertEquals(uuid, c.parseLiteral(StringValue(text)))
        assertEquals(uuid, c.parseValue(JsonPrimitive(text)))
        assertEquals(uuid, c.parseValue(uuid))
        assertEquals(JsonPrimitive(text), c.serialize(uuid))
        assertEquals(JsonPrimitive(text), c.serialize(text))
        assertFailsWith<CoercingException> { c.parseLiteral(StringValue("not-a-uuid")) }
        assertFailsWith<CoercingException> { c.parseLiteral(IntValue("1")) }
        assertFailsWith<CoercingException> { c.parseValue(JsonPrimitive(5)) } // non-string/non-uuid value
        assertFailsWith<CoercingException> { c.serialize(1) }
    }

    @Test
    fun `DateTime round-trips ISO-8601 instants`() {
        val c = ExtendedScalars.DateTime
        val text = "2026-01-01T00:00:00Z"
        val instant = Instant.parse(text)
        assertEquals(instant, c.parseLiteral(StringValue(text)))
        assertEquals(instant, c.parseValue(JsonPrimitive(text)))
        assertEquals(instant, c.parseValue(instant))
        assertEquals(JsonPrimitive(text), c.serialize(instant))
        assertEquals(JsonPrimitive(text), c.serialize(text))
        assertFailsWith<CoercingException> { c.parseLiteral(StringValue("nope")) }
        assertFailsWith<CoercingException> { c.parseValue(JsonPrimitive(5)) }
        assertFailsWith<CoercingException> { c.serialize(5) }
    }

    @Test
    fun `JSON serializes primitives, null, and elements, and parses every literal kind`() {
        val c = ExtendedScalars.Json
        // serialize: null, primitives, and pass-through elements
        assertEquals(JsonNull, c.serialize(null))
        assertEquals(JsonPrimitive(true), c.serialize(true))
        assertEquals(JsonPrimitive(1), c.serialize(1))
        assertEquals(JsonPrimitive(2L), c.serialize(2L))
        assertEquals(JsonPrimitive(1.5), c.serialize(1.5))
        assertEquals(JsonPrimitive("x"), c.serialize("x"))
        val element = JsonObject(mapOf("a" to JsonPrimitive(1)))
        assertEquals(element, c.serialize(element))
        assertEquals(element, c.parseValue(element))
        assertEquals(JsonPrimitive("v"), c.parseValue("v")) // non-element primitive is wrapped
        assertFailsWith<CoercingException> { c.serialize(Any()) }
        // parseLiteral over every literal kind (drives astToJson)
        assertEquals(JsonPrimitive(1L), c.parseLiteral(IntValue("1")))
        assertEquals(JsonPrimitive(1.5), c.parseLiteral(FloatValue("1.5")))
        assertEquals(JsonPrimitive("s"), c.parseLiteral(StringValue("s")))
        assertEquals(JsonPrimitive(true), c.parseLiteral(BooleanValue(true)))
        assertEquals(JsonNull, c.parseLiteral(NullValue()))
        assertEquals(JsonPrimitive("E"), c.parseLiteral(EnumValue("E")))
        assertEquals(JsonArray(listOf(JsonPrimitive(1L))), c.parseLiteral(ListValue(listOf(IntValue("1")))))
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1L))), c.parseLiteral(ObjectValue(listOf(ObjectField("a", IntValue("1"))))))
        assertFailsWith<CoercingException> { c.parseLiteral(Variable("v")) } // a variable cannot appear in a const value
    }

    @Test
    fun `Upload is input-only`() {
        val c = ExtendedScalars.Upload
        val file = Any()
        assertEquals(file, c.parseValue(file)) // the multipart transport injects the file object
        assertFailsWith<CoercingException> { c.parseValue(null) }
        assertFailsWith<CoercingException> { c.parseLiteral(StringValue("x")) }
        assertFailsWith<CoercingException> { c.serialize(file) }
    }

    @Test
    fun `the extended-scalar registry exposes every custom scalar`() {
        assertTrue(ExtendedScalars.coercings.keys.containsAll(setOf("Long", "UUID", "DateTime", "JSON", "Upload")))
    }
}

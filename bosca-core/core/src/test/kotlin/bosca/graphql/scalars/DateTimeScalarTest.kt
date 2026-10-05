package bosca.graphql.scalars

import bosca.graphql.language.IntValue
import bosca.graphql.language.StringValue
import bosca.graphql.server.CoercingException
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DateTimeScalarTest {
    private val coercing = DateTime.Type
    private val value = OffsetDateTime.parse("2026-08-04T12:34:56Z")

    @Test
    fun `serialize and parse value accept datetime strings objects and json primitives`() {
        assertEquals(JsonPrimitive(value.toString()), coercing.serialize(value))
        assertEquals(JsonPrimitive(value.toString()), coercing.serialize(value.toString()))
        assertEquals(value, coercing.parseValue(value))
        assertEquals(value, coercing.parseValue(value.toString()))
        assertEquals(value, coercing.parseValue(JsonPrimitive(value.toString())))
    }

    @Test
    fun `literal parsing accepts strings and rejects invalid forms`() {
        assertEquals(value, coercing.parseLiteral(StringValue(value.toString())))
        assertFailsWith<CoercingException> { coercing.parseLiteral(IntValue("1")) }
        assertFailsWith<CoercingException> { coercing.parseLiteral(StringValue("invalid")) }
    }

    @Test
    fun `value coercion rejects invalid strings and unrelated values`() {
        assertFailsWith<CoercingException> { coercing.serialize("not-a-date") }
        assertFailsWith<CoercingException> { coercing.serialize(123) }
        assertFailsWith<CoercingException> { coercing.parseValue(JsonPrimitive(123)) }
    }
}

package bosca.graphql.scalars

import bosca.graphql.language.IntValue
import bosca.graphql.language.StringValue
import bosca.graphql.server.CoercingException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class UUIDScalarTest {

    private val coercing = UUID.Type

    @Test
    fun `serialize returns the UUID string`() {
        val uuid = Uuid.random()
        assertEquals(JsonPrimitive(uuid.toString()), coercing.serialize(uuid))
        assertEquals(JsonPrimitive(uuid.toString()), coercing.serialize(uuid.toString()))
    }

    @Test
    fun `serialize rejects invalid values`() {
        assertFailsWith<CoercingException> { coercing.serialize(12345) }
        assertFailsWith<CoercingException> { coercing.serialize("not-a-uuid") }
    }

    @Test
    fun `parseValue accepts UUID and valid string`() {
        val uuid = Uuid.random()
        assertEquals(uuid, coercing.parseValue(uuid))
        assertEquals(uuid, coercing.parseValue(uuid.toString()))
    }

    @Test
    fun `parseValue rejects invalid values`() {
        assertFailsWith<CoercingException> { coercing.parseValue("not-a-uuid") }
        assertFailsWith<CoercingException> { coercing.parseValue(12345) }
    }

    @Test
    fun `parseLiteral accepts valid string and rejects other literals`() {
        val uuid = Uuid.random()
        assertEquals(uuid, coercing.parseLiteral(StringValue(uuid.toString())))
        assertFailsWith<CoercingException> { coercing.parseLiteral(IntValue("1")) }
        assertFailsWith<CoercingException> { coercing.parseLiteral(StringValue("not-a-uuid")) }
    }
}

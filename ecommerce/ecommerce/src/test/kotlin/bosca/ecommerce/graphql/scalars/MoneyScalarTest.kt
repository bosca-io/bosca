package bosca.ecommerce.graphql.scalars

import bosca.ecommerce.model.Money
import bosca.graphql.language.IntValue
import bosca.graphql.language.StringValue
import bosca.graphql.server.CoercingException
import java.math.BigDecimal
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyScalarTest {

    private val coercing = MoneyScalar.Type

    @Test
    fun `serialize accepts Money, BigDecimal, and String`() {
        assertEquals(JsonPrimitive("19.9900"), coercing.serialize(Money.of("19.99")))
        assertEquals(JsonPrimitive("5.0000"), coercing.serialize(BigDecimal("5")))
        assertEquals(JsonPrimitive("3.5000"), coercing.serialize("3.5"))
    }

    @Test
    fun `serialize rejects an unsupported type`() {
        assertFailsWith<CoercingException> { coercing.serialize(42) }
    }

    @Test
    fun `parseValue accepts Money, JSON string, String, and Number`() {
        assertEquals(Money.of("19.99"), coercing.parseValue(Money.of("19.99")))
        assertEquals(Money.of("7.25"), coercing.parseValue(JsonPrimitive("7.25")))
        assertEquals(Money.of("7.25"), coercing.parseValue("7.25"))
        assertEquals(Money.of("8"), coercing.parseValue(8))
    }

    @Test
    fun `parseValue rejects unsupported and invalid values`() {
        assertFailsWith<CoercingException> { coercing.parseValue(listOf(1)) }
        assertFailsWith<CoercingException> { coercing.parseValue("not-money") }
    }

    @Test
    fun `parseLiteral accepts string and rejects non-string`() {
        assertEquals(Money.of("12.50"), coercing.parseLiteral(StringValue("12.50")))
        assertFailsWith<CoercingException> { coercing.parseLiteral(IntValue("5")) }
    }
}

package bosca.ecommerce.graphql.scalars

import bosca.ecommerce.model.Money
import bosca.graphql.language.StringValue
import bosca.graphql.language.Value
import bosca.graphql.server.Coercing
import bosca.graphql.server.CoercingException
import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The `Money` scalar, represented on the wire as a fixed scale-4 decimal string. */
object MoneyScalar {

    val Type: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement = JsonPrimitive(
            when (value) {
                is Money -> value.amount.toPlainString()
                is BigDecimal -> Money(value).amount.toPlainString()
                is String -> parse(value).amount.toPlainString()
                else -> throw CoercingException("Expected a Money value but was ${value?.let { it::class.simpleName }}")
            },
        )

        override fun parseValue(input: Any?): Any = when (input) {
            is Money -> input
            is JsonPrimitive -> parse(input.content)
            is String -> parse(input)
            is Number -> parse(input.toString())
            else -> throw CoercingException("Expected a Money string but was ${input?.let { it::class.simpleName }}")
        }

        override fun parseLiteral(literal: Value): Any =
            if (literal is StringValue) parse(literal.value)
            else throw CoercingException("Expected a string Money literal but was ${literal::class.simpleName}")

        private fun parse(value: String): Money = try {
            Money.of(value)
        } catch (_: NumberFormatException) {
            throw CoercingException("Invalid Money value: \"$value\"")
        }
    }
}

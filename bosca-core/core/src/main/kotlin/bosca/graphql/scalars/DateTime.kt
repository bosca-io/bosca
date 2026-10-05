package bosca.graphql.scalars

import bosca.graphql.language.StringValue
import bosca.graphql.language.Value
import bosca.graphql.server.Coercing
import bosca.graphql.server.CoercingException
import java.time.DateTimeException
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

object DateTime {
    val Type: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement = JsonPrimitive(asDateTime(value).toString())

        override fun parseValue(input: Any?): Any = asDateTime(
            if (input is JsonPrimitive && input.isString) input.content else input,
        )

        override fun parseLiteral(literal: Value): Any {
            val value = (literal as? StringValue)?.value
                ?: throw CoercingException("Expected a DateTime string literal")
            return parse(value)
        }

        private fun asDateTime(value: Any?): OffsetDateTime = when (value) {
            is OffsetDateTime -> value
            is String -> parse(value)
            else -> throw CoercingException("Expected a DateTime value, got $value")
        }

        private fun parse(value: String): OffsetDateTime = try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeException) {
            throw CoercingException("Invalid ISO-8601 DateTime: '$value'")
        }
    }
}

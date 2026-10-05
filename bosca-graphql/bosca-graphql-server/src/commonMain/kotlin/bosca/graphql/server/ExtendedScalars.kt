@file:OptIn(ExperimentalUuidApi::class)

package bosca.graphql.server

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.IntValue
import bosca.graphql.language.StringValue
import bosca.graphql.language.Value
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The custom scalars the platform uses, beyond the five built-ins. Register the ones a schema declares via the
 * wiring DSL, e.g. `scalar("UUID", ExtendedScalars.Uuid)`, or wire them all from [coercings].
 *
 * Internal value types: Long → [Long], UUID → [kotlin.uuid.Uuid] (matching `bosca.serialization.UUID`),
 * DateTime → [kotlinx.datetime.Instant], JSON → a [JsonElement] (with recursive [Map] and [Iterable] compatibility
 * for resolver output), Upload → the transport-injected file object (input-only — Uploads arrive via multipart,
 * never in a literal or a response).
 */
object ExtendedScalars {

    val Long: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement = JsonPrimitive(asLong(value) ?: throw CoercingException("Cannot serialize '$value' as Long"))
        override fun parseValue(input: Any?): Any = asLong(unwrap(input)) ?: throw CoercingException("Expected a Long value, got $input")
        override fun parseLiteral(literal: Value): Any {
            if (literal !is IntValue) throw CoercingException("Expected a Long literal, got ${literal::class.simpleName}")
            return literal.value.toLongOrNull() ?: throw CoercingException("Long literal '${literal.value}' is out of range")
        }

        private fun asLong(value: Any?): kotlin.Long? = when (value) {
            is kotlin.Long -> value
            is Int -> value.toLong()
            else -> null
        }
    }

    val Uuid: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement = when (value) {
            is kotlin.uuid.Uuid -> JsonPrimitive(value.toString())
            is String -> JsonPrimitive(parse(value).toString())
            else -> throw CoercingException("Cannot serialize '$value' as UUID")
        }
        override fun parseValue(input: Any?): Any = when (val v = unwrap(input)) {
            is kotlin.uuid.Uuid -> v
            is String -> parse(v)
            else -> throw CoercingException("Expected a UUID value, got $input")
        }
        override fun parseLiteral(literal: Value): Any {
            if (literal !is StringValue) throw CoercingException("Expected a UUID string literal, got ${literal::class.simpleName}")
            return parse(literal.value)
        }

        private fun parse(value: String): kotlin.uuid.Uuid =
            try {
                kotlin.uuid.Uuid.parse(value) // FQ: the `Uuid` property below would shadow the class
            } catch (_: IllegalArgumentException) {
                throw CoercingException("Invalid UUID: '$value'")
            }
    }

    val DateTime: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement = when (value) {
            is Instant -> JsonPrimitive(value.toString())
            is String -> JsonPrimitive(parse(value).toString())
            else -> throw CoercingException("Cannot serialize '$value' as DateTime")
        }
        override fun parseValue(input: Any?): Any = when (val v = unwrap(input)) {
            is Instant -> v
            is String -> parse(v)
            else -> throw CoercingException("Expected a DateTime value, got $input")
        }
        override fun parseLiteral(literal: Value): Any {
            if (literal !is StringValue) throw CoercingException("Expected a DateTime string literal, got ${literal::class.simpleName}")
            return parse(literal.value)
        }

        private fun parse(value: String): Instant =
            try {
                Instant.parse(value)
            } catch (_: IllegalArgumentException) {
                throw CoercingException("Invalid ISO-8601 DateTime: '$value'")
            }
    }

    val Json: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement = toJson(value)
        override fun parseValue(input: Any?): Any = if (input is JsonElement) input else toJson(unwrap(input))
        override fun parseLiteral(literal: Value): Any = astToJson(literal)

        private fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is kotlin.Long -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(
                value.map { (key, nestedValue) ->
                    val fieldName = key as? String
                        ?: throw CoercingException("Cannot represent a JSON object with non-string key '$key'")
                    fieldName to toJson(nestedValue)
                }.toMap(),
            )
            is Iterable<*> -> JsonArray(value.map(::toJson))
            else -> throw CoercingException("Cannot represent '$value' as JSON; resolvers should return a JsonElement")
        }
    }

    /** Input-only: an Upload is injected by the multipart transport, so it never serializes or appears as a literal. */
    val Upload: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement = throw CoercingException("'Upload' is an input-only type and cannot be returned")
        override fun parseValue(input: Any?): Any = input ?: throw CoercingException("'Upload' must be provided as a multipart file")
        override fun parseLiteral(literal: Value): Any = throw CoercingException("'Upload' cannot appear as a literal; pass it as a variable")
    }

    val coercings: Map<String, Coercing> = mapOf(
        "Long" to Long,
        "UUID" to Uuid,
        "DateTime" to DateTime,
        "JSON" to Json,
        "Upload" to Upload,
    )
}

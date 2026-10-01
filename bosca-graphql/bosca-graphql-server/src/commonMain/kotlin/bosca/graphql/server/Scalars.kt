package bosca.graphql.server

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.EnumValue
import bosca.graphql.language.FloatValue
import bosca.graphql.language.IntValue
import bosca.graphql.language.ListValue
import bosca.graphql.language.NullValue
import bosca.graphql.language.ObjectValue
import bosca.graphql.language.StringValue
import bosca.graphql.language.Value
import bosca.graphql.language.Variable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** The five spec built-in scalars, always available to an [ExecutableSchema] without explicit wiring. */
object Scalars {
    val coercings: Map<String, Coercing> = mapOf(
        "Int" to IntCoercing,
        "Float" to FloatCoercing,
        "String" to StringCoercing,
        "Boolean" to BooleanCoercing,
        "ID" to IdCoercing,
    )
}

/** Reduce a JSON wire value to its plain Kotlin form so each coercing can pattern-match uniformly. */
internal fun unwrap(input: Any?): Any? = when (input) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        input.isString -> input.content
        input.booleanOrNull != null -> input.booleanOrNull
        input.longOrNull != null -> input.longOrNull
        input.doubleOrNull != null -> input.doubleOrNull
        else -> input.content
    }
    else -> input
}

/** Convert a const AST literal to JSON (for the JSON scalar + literal coercion); rejects variables. */
internal fun astToJson(literal: Value): JsonElement = when (literal) {
    is IntValue -> JsonPrimitive(literal.value.toLongOrNull() ?: throw CoercingException("Int literal '${literal.value}' is out of range"))
    is FloatValue -> JsonPrimitive(literal.value.toDouble().takeIf { it.isFinite() } ?: throw CoercingException("Float literal '${literal.value}' is not finite"))
    is StringValue -> JsonPrimitive(literal.value)
    is BooleanValue -> JsonPrimitive(literal.value)
    is NullValue -> JsonNull
    is EnumValue -> JsonPrimitive(literal.value)
    is ListValue -> JsonArray(literal.values.map { astToJson(it) })
    is ObjectValue -> JsonObject(literal.fields.associate { it.name to astToJson(it.value) })
    is Variable -> throw CoercingException("A variable cannot appear in a const value")
}

private object IntCoercing : Coercing {
    override fun serialize(value: Any?): JsonElement = JsonPrimitive(asInt(value) ?: fail("serialize", value))
    override fun parseValue(input: Any?): Any = asInt(unwrap(input)) ?: fail("parse", input)
    override fun parseLiteral(literal: Value): Any {
        val intLiteral = literal as? IntValue ?: throw CoercingException("Expected an Int literal, got ${literal::class.simpleName}")
        return intLiteral.value.toIntOrNull() ?: throw CoercingException("Int literal '${intLiteral.value}' is out of range")
    }

    private fun asInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> {
            val narrowed = value.toInt()
            if (narrowed.toLong() == value) narrowed else null // reject overflow
        }
        else -> null
    }
    private fun fail(op: String, value: Any?): Nothing = throw CoercingException("Cannot $op '$value' as Int")
}

private object FloatCoercing : Coercing {
    override fun serialize(value: Any?): JsonElement = JsonPrimitive(asDouble(value) ?: fail("serialize", value))
    override fun parseValue(input: Any?): Any = asDouble(unwrap(input)) ?: fail("parse", input)
    override fun parseLiteral(literal: Value): Any = when (literal) {
        is FloatValue -> literalDouble(literal.value)
        is IntValue -> literalDouble(literal.value)
        else -> throw CoercingException("Expected a Float literal, got ${literal::class.simpleName}")
    }

    private fun asDouble(value: Any?): Double? = when (value) {
        is Double -> finite(value)
        is Float -> finite(value.toDouble())
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        else -> null
    }

    private fun literalDouble(raw: String): Double =
        finite(raw.toDouble()) ?: throw CoercingException("Float literal '$raw' is not a finite value")

    // The GraphQL Float type is finite IEEE-754 double precision: NaN and ±Infinity are not valid values (§3.5.2).
    private fun finite(value: Double): Double? = if (value.isFinite()) value else null
    private fun fail(op: String, value: Any?): Nothing = throw CoercingException("Cannot $op '$value' as Float")
}

private object StringCoercing : Coercing {
    override fun serialize(value: Any?): JsonElement {
        if (value == null) throw CoercingException("Cannot serialize null as String")
        return JsonPrimitive(value.toString())
    }
    override fun parseValue(input: Any?): Any = unwrap(input) as? String ?: throw CoercingException("Expected a String value, got $input")
    override fun parseLiteral(literal: Value): Any {
        if (literal !is StringValue) throw CoercingException("Expected a String literal, got ${literal::class.simpleName}")
        return literal.value
    }
}

private object BooleanCoercing : Coercing {
    override fun serialize(value: Any?): JsonElement =
        JsonPrimitive(value as? Boolean ?: throw CoercingException("Cannot serialize '$value' as Boolean"))
    override fun parseValue(input: Any?): Any = unwrap(input) as? Boolean ?: throw CoercingException("Expected a Boolean value, got $input")
    override fun parseLiteral(literal: Value): Any {
        if (literal !is BooleanValue) throw CoercingException("Expected a Boolean literal, got ${literal::class.simpleName}")
        return literal.value
    }
}

private object IdCoercing : Coercing {
    override fun serialize(value: Any?): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is Int, is Long -> JsonPrimitive(value.toString())
        else -> throw CoercingException("Cannot serialize '$value' as ID")
    }
    override fun parseValue(input: Any?): Any = when (val v = unwrap(input)) {
        is String -> v
        is Int -> v.toString()
        is Long -> v.toString()
        else -> throw CoercingException("Expected a String or Int ID value, got $input")
    }
    override fun parseLiteral(literal: Value): Any = when (literal) {
        is StringValue -> literal.value
        is IntValue -> literal.value
        else -> throw CoercingException("Expected a String or Int ID literal, got ${literal::class.simpleName}")
    }
}

package bosca.graphql.server

import bosca.graphql.language.Value
import kotlinx.serialization.json.JsonElement

/**
 * Translates a scalar between its three forms (graphql-java's `Coercing`):
 * - [serialize]: an internal value → its output (wire) JSON, for the response.
 * - [parseValue]: an input value from request variables → the internal value.
 * - [parseLiteral]: an AST literal in the query → the internal value.
 *
 * A failure throws [CoercingException]; the coercion/execution layer attaches the location + path.
 */
interface Coercing {
    fun serialize(value: Any?): JsonElement
    fun parseValue(input: Any?): Any?
    fun parseLiteral(literal: Value): Any?
}

/** A scalar coercion failure (mirrors graphql-java's `CoercingParseValue` / `CoercingSerialize` exceptions). */
class CoercingException(val reason: String) : RuntimeException(reason)

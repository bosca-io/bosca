package bosca.graphql.server

import bosca.graphql.language.SourceLocation

/** An operation's variables, coerced to internal values (graphql-java's `CoercedVariables`). An absent variable is omitted; a provided-null variable maps to `null`. */
class CoercedVariables(val values: Map<String, Any?>) {
    companion object {
        val EMPTY = CoercedVariables(emptyMap())
    }
}

/** A value/variable coercion failure carrying the [reason], the [path] (field names + list indices), and [location]. */
class CoercionException(val reason: String, val path: List<Any>, val location: SourceLocation?) : RuntimeException(reason)

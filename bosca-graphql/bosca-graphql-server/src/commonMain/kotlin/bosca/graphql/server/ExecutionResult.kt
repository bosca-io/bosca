package bosca.graphql.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The outcome of executing an operation. [data] is the resolved tree — a `JsonObject`/`JsonNull` when execution
 * ran (possibly with nulled fields alongside [errors]), or Kotlin `null` when a pre-execution request error
 * (parse/validate/coercion) meant nothing ran. [errors] holds any field/request errors.
 */
data class ExecutionResult(
    val data: JsonElement? = null,
    val errors: List<GraphQLError> = emptyList(),
    val extensions: JsonObject? = null,
) {
    /** The spec response (§7.1.2): `data` when execution ran, `errors` when non-empty, optional `extensions`. */
    fun toJson(): JsonObject = buildJsonObject {
        if (data != null) put("data", data)
        if (errors.isNotEmpty()) put("errors", JsonArray(errors.map { it.toJson() }))
        if (extensions != null) put("extensions", extensions)
    }

    companion object {
        /** A pre-execution request error (no `data` key in the response). */
        fun ofErrors(errors: List<GraphQLError>): ExecutionResult = ExecutionResult(data = null, errors = errors)
    }
}

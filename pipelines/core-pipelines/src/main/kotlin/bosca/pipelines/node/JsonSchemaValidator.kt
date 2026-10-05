package bosca.pipelines.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates a JSON value against a pipeline input/output schema — a pragmatic **subset of JSON
 * Schema**: `type` (`object`/`array`/`string`/`number`/`integer`/`boolean`/`null`), `properties`,
 * `required`, `items` and `enum`, applied recursively. Anything beyond the subset is ignored
 * rather than rejected, so schemas authored with extra JSON Schema keywords still validate on
 * what the engine understands.
 *
 * Hand-rolled over [JsonElement] (no schema library): the engine compiles to GraalVM native and
 * must stay reflection-free, and the platform's tool-input validation already follows this
 * pattern. Returns every violation (with a JSON-path-style location), not just the first, so the
 * editor and run errors can show the full picture.
 */
object JsonSchemaValidator {

    /** All violations of [schema] by [value], or an empty list when the value conforms. */
    fun validate(value: JsonElement, schema: JsonElement): List<String> {
        if (schema !is JsonObject) return emptyList()
        val violations = mutableListOf<String>()
        validate(value, schema, "$", violations)
        return violations
    }

    private fun validate(value: JsonElement, schema: JsonObject, path: String, violations: MutableList<String>) {
        val expectedType = (schema["type"] as? JsonPrimitive)?.content
        if (expectedType != null && !matchesType(value, expectedType)) {
            violations += "$path: expected $expectedType but got ${describe(value)}"
            return
        }
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) {
                violations += "$path: must be one of ${allowed.joinToString()}"
                return
            }
        }
        if (value is JsonObject) {
            val required = (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            required.filter { it !in value }.forEach { violations += "$path: missing required field '$it'" }
            val properties = schema["properties"] as? JsonObject ?: return
            for ((key, propertyValue) in value) {
                val propertySchema = properties[key] as? JsonObject ?: continue
                if (propertyValue is JsonNull && key !in required) continue
                validate(propertyValue, propertySchema, "$path.$key", violations)
            }
        }
        if (value is JsonArray) {
            val itemSchema = schema["items"] as? JsonObject ?: return
            value.forEachIndexed { index, item -> validate(item, itemSchema, "$path[$index]", violations) }
        }
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
        "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.content.toBooleanStrictOrNull() != null
        "null" -> value is JsonNull
        else -> true
    }

    private fun describe(value: JsonElement): String = when (value) {
        is JsonObject -> "an object"
        is JsonArray -> "an array"
        is JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) "a string" else "a ${primitiveKind(value)}"
    }

    private fun primitiveKind(value: JsonPrimitive): String = when {
        value.content.toBooleanStrictOrNull() != null -> "boolean"
        value.content.toLongOrNull() != null -> "integer"
        value.content.toDoubleOrNull() != null -> "number"
        else -> "primitive"
    }
}

package bosca.experimentation.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Validates that a [JsonElement] value is shape-compatible with a [FlagType].
 *
 * Used by both flag and experiment write paths so that defaultValues, targeting rule
 * rollout values, and experiment variant values are all guaranteed to match the
 * declared type of their parent flag. Without this enforcement, a client could
 * receive a flag value of an unexpected JSON type at evaluation time and crash.
 *
 * The validator is a stateless object so it can be referenced from any service
 * without dependency injection — there's nothing to configure and nothing to mock.
 */
object FlagValueValidator {

    /**
     * Throws [IllegalArgumentException] when [value] does not match [type].
     *
     * @param type the declared flag type
     * @param value the value to validate
     * @param context a short label included in the error message identifying which
     *        field is being validated (e.g., "default value", "variant 'control' value")
     *        so callers don't have to wrap the exception themselves
     */
    fun validate(type: FlagType, value: JsonElement, context: String = "value") {
        val problem = check(type, value)
        if (problem != null) {
            throw IllegalArgumentException("Invalid $context for ${type.name} flag: $problem")
        }
    }

    /**
     * Returns null if [value] is valid for [type], or a human-readable problem
     * description otherwise. Prefer [validate] in service code; this variant exists
     * for places that want to surface multiple problems at once without throwing.
     */
    fun check(type: FlagType, value: JsonElement): String? {
        if (value is JsonNull) return "value cannot be null"
        return when (type) {
            FlagType.BOOLEAN -> {
                val prim = value as? JsonPrimitive
                if (prim == null || prim.isString || prim.booleanOrNull == null) {
                    "expected a boolean (true or false), got ${describe(value)}"
                } else null
            }
            FlagType.STRING -> {
                val prim = value as? JsonPrimitive
                if (prim == null || !prim.isString) {
                    "expected a string, got ${describe(value)}"
                } else null
            }
            FlagType.PERCENTAGE -> {
                val prim = value as? JsonPrimitive
                val num = prim?.doubleOrNull
                if (prim == null || prim.isString || num == null) {
                    "expected a number between 0 and 100, got ${describe(value)}"
                } else if (num < 0.0 || num > 100.0) {
                    "expected a number between 0 and 100, got $num"
                } else null
            }
            FlagType.JSON -> null // any non-null JsonElement is acceptable
        }
    }

    /**
     * Validates that every variation in a flag's variation list has a value matching
     * the flag type. Throws on the first invalid variation, identifying it by key.
     */
    fun validateVariations(type: FlagType, variations: List<Variation>) {
        require(variations.isNotEmpty()) { "Feature flag must have at least one variation" }
        val seen = mutableSetOf<String>()
        for (variation in variations) {
            require(seen.add(variation.key)) { "Duplicate variation key: ${variation.key}" }
            // Belt-and-suspenders: the live-distribution prune path joins
            // variation keys on U+001F into a single delimited string and
            // legacy code joined on `,`. Reject either character in a key
            // so neither delimiter can split a legitimate key into two
            // pseudo-keys downstream.
            require(',' !in variation.key && '\u001f' !in variation.key) {
                "Variation key '${variation.key}' must not contain ',' or U+001F"
            }
            validate(type, variation.value, "variation '${variation.key}' value")
        }
    }

    private fun describe(value: JsonElement): String {
        if (value is JsonPrimitive) {
            return when {
                value.isString -> "string \"${value.content}\""
                value.booleanOrNull != null -> "boolean ${value.content}"
                value.doubleOrNull != null -> "number ${value.content}"
                else -> "primitive ${value.content}"
            }
        }
        return value::class.simpleName ?: "unknown"
    }
}

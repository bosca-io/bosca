package bosca.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KProperty

/**
 * A multimap of string key-value pairs representing HTTP query parameters, form parameters,
 * or path parameters extracted from the request.
 *
 * Supports delegate property access via Kotlin's `by` keyword for concise parameter extraction
 * in route handlers.
 */
class Parameters(private val map: Map<String, List<String>> = emptyMap()) {

    /** Returns the first value for the given [name], or null if not present. */
    operator fun get(name: String): String? = map[name]?.firstOrNull()

    /** Returns all values for the given [name], or an empty list if not present. */
    fun getAll(name: String): List<String> = map[name] ?: emptyList()

    /** Returns true if the given [name] is present in the parameters. */
    operator fun contains(name: String): Boolean = map.containsKey(name)

    /** Returns all parameter names. */
    val names: Set<String> get() = map.keys

    /** Returns true if there are no parameters. */
    val isEmpty: Boolean get() = map.isEmpty()

    /** Delegate accessor that retrieves the parameter value matching the property name. */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
        return get(property.name) ?: throw IllegalStateException("Missing parameter: ${property.name}")
    }

    /**
     * Returns the first value for [name] cast to the requested type, or null if not present.
     * Supports String types; other types are not currently supported.
     */
    inline fun <reified R : Any> getOrNull(name: String): R? {
        val value = get(name) ?: return null
        @Suppress("UNCHECKED_CAST")
        return when (R::class) {
            String::class -> value as? R
            Int::class -> value.toIntOrNull() as? R
            Long::class -> value.toLongOrNull() as? R
            Double::class -> value.toDoubleOrNull() as? R
            Boolean::class -> value.toBooleanStrictOrNull() as? R
            Float::class -> value.toFloatOrNull() as? R
            else -> value as? R
        }
    }

    /**
     * Returns the first value for [name] cast to the requested type, throwing if not present.
     * Supports String, Int, Long, Boolean, and Float types.
     */
    inline fun <reified R : Any> getOrFail(name: String): R {
        val value = get(name) ?: throw IllegalArgumentException("Missing required parameter: $name")
        @Suppress("UNCHECKED_CAST")
        return when (R::class) {
            String::class -> value as R
            Int::class -> (value.toIntOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Int")) as R
            Long::class -> (value.toLongOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Long")) as R
            Double::class -> (value.toDoubleOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Double")) as R
            Boolean::class -> (value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Boolean")) as R
            Float::class -> (value.toFloatOrNull() ?: throw IllegalArgumentException("Cannot convert '$value' to Float")) as R
            else -> value as R
        }
    }

    /**
     * Converts these parameters to a [JsonObject] where each parameter name maps to
     * a [JsonArray] of its string values. Always uses arrays for consistency so that
     * scripts do not need to handle both single-value and multi-value cases.
     */
    fun toJsonElement(): JsonElement =
        JsonObject(map.mapValues { (_, values) ->
            JsonArray(values.map { JsonPrimitive(it) })
        })

    companion object {
        val Empty = Parameters()

        /** Builds [Parameters] from a map of name to single-value pairs. */
        fun fromSingleValueMap(map: Map<String, String>): Parameters =
            if (map.isEmpty()) Empty else Parameters(map.mapValues { listOf(it.value) })

        /** Builds [Parameters] from a list of name-value pairs. */
        fun fromPairs(pairs: List<Pair<String, String>>): Parameters =
            Parameters(pairs.groupBy({ it.first }, { it.second }))
    }
}

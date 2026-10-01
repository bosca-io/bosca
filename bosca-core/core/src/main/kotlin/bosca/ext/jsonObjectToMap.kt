package bosca.ext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, v) -> jsonElementToAny(v) }

private fun jsonArrayToList(array: JsonArray): List<Any?> = array.map { jsonElementToAny(it) }

private fun jsonElementToAny(el: JsonElement): Any? = when (el) {
    is JsonNull -> null
    is JsonObject -> jsonObjectToMap(el)
    is JsonArray -> jsonArrayToList(el)
    is JsonPrimitive -> {
        if (el.isString) {
            el.content
        } else {
            val c = el.content
            when {
                c.equals("true", ignoreCase = true) -> true
                c.equals("false", ignoreCase = true) -> false
                c.contains('.') -> c.toDoubleOrNull() ?: c
                else -> el.content.toLongOrNull() ?: el.content.toDoubleOrNull() ?: c
            }
        }
    }
}

fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) })
    is Iterable<*> -> JsonArray(value.map { anyToJsonElement(it) })
    is Array<*> -> JsonArray(value.map { anyToJsonElement(it) })
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
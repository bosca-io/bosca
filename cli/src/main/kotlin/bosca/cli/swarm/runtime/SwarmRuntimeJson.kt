package bosca.cli.swarm.runtime

import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun JsonObject.objectAt(key: String) = (get(key) as? JsonObject) ?: JsonObject(emptyMap())
internal fun JsonObject.textAt(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.longAt(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject.boolAt(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.arrayAt(key: String) = (get(key) as? JsonArray).orEmpty()
internal fun JsonObject.updated(key: String, value: JsonElement) = JsonObject(this + (key to value))
internal fun canonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(value.map(::canonical))
    else -> value
}
internal fun filters(key: String, vararg values: String) = "?filters=" + URLEncoder.encode(
    buildJsonObject { put(key, JsonArray(values.map(::JsonPrimitive))) }.toString(), StandardCharsets.UTF_8)

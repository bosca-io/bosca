package bosca.system.ui

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Structured payloads use the JSON editor; other UTF-8 values retain their original text. */
fun systemConsoleJson(value: String?): JsonElement? {
    if (value.isNullOrEmpty()) return null
    return try { Json.parseToJsonElement(value).takeIf { it is JsonObject || it is JsonArray } }
    catch (_: SerializationException) { null }
}

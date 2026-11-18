package bosca.system.ui

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

fun systemMeilisearchTaskTone(status: String): String = when (status.lowercase()) {
    "succeeded" -> "success"; "failed" -> "error"; "processing" -> "info"; "enqueued" -> "warning"; else -> "neutral"
}

fun systemMeilisearchDocument(value: JsonElement): JsonElement =
    if (value is JsonPrimitive && value.isString) systemConsoleJson(value.content) ?: value else value

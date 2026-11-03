package bosca.system.ui

import kotlinx.serialization.json.*

internal fun systemConfigurationObject(value: JsonElement?): JsonObject {
    require(value == null || value is JsonNull || value is JsonObject) { "Configuration must contain a JSON object." }
    return value as? JsonObject ?: JsonObject(emptyMap())
}

internal fun systemStoredSecret(input: String, clear: Boolean, stored: JsonElement?): String =
    if (clear) "" else input.takeIf(String::isNotEmpty) ?: (stored as? JsonPrimitive)?.contentOrNull.orEmpty()

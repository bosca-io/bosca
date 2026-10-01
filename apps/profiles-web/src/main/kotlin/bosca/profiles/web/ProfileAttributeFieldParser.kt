package bosca.profiles.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val prettyJson = Json { prettyPrint = true }

internal fun JsonElement?.asEditableJson(): String =
    prettyJson.encodeToString(JsonElement.serializer(), this ?: JsonNull)

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun profileAttributeField(
    schema: JsonElement?,
    uiSchema: JsonElement?,
    attributes: JsonElement? = null,
): ProfileAttributeFieldRow {
    val schemaObject = schema as? JsonObject ?: return ProfileAttributeFieldRow()
    val properties = schemaObject["properties"] as? JsonObject ?: return ProfileAttributeFieldRow()
    val uiField = ((uiSchema as? JsonObject)?.get("layout") as? JsonArray)
        ?.firstOrNull { (it as? JsonObject)?.string("type") == "field" } as? JsonObject
    val key = uiField?.string("property")?.takeIf(properties::containsKey)
        ?: properties.keys.firstOrNull().orEmpty()
    if (key.isBlank()) return ProfileAttributeFieldRow()

    val property = properties[key] as? JsonObject ?: return ProfileAttributeFieldRow()
    val type = property.string("type")
    val control = uiField?.string("control").orEmpty()
    val value = (attributes as? JsonObject)?.get(key) as? JsonPrimitive
    val required = (schemaObject["required"] as? JsonArray).orEmpty().any {
        it.jsonPrimitive.contentOrNull == key
    }
    val options = (property["enum"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    return ProfileAttributeFieldRow(
        key = key,
        label = uiField?.string("label").orEmpty().ifBlank {
            property.string("title").ifBlank { key.replaceFirstChar(Char::uppercase) }
        },
        description = uiField?.string("description").orEmpty().ifBlank { property.string("description") },
        placeholder = uiField?.string("placeholder").orEmpty(),
        inputType = when {
            type == "number" || type == "integer" -> "number"
            property.string("format") == "email" -> "email"
            property.string("format") == "uri" -> "url"
            else -> "text"
        },
        value = value?.contentOrNull.orEmpty(),
        required = required,
        textarea = control == "textarea",
        boolean = type == "boolean" || control == "switch" || control == "checkbox",
        checked = value?.booleanOrNull == true,
        rows = uiField?.get("rows")?.jsonPrimitive?.intOrNull?.coerceIn(2, 20) ?: 4,
        options = options,
    )
}

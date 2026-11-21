package bosca.design.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Presentation of a Forms layout node; the original JSON schemas remain authoritative. */
@Serializable
data class SchemaFormNode(
    val key: String,
    val type: String,
    val label: String = "",
    val property: String = "",
    val control: String = "",
    val schema: JsonObject = JsonObject(emptyMap()),
    val configuration: JsonObject = JsonObject(emptyMap()),
    val value: JsonElement = JsonNull,
    val required: Boolean = false,
    val error: String = "",
    val columns: Int = 12,
    val options: List<SchemaFormOption> = emptyList(),
    val children: List<SchemaFormNode> = emptyList(),
) {
    val text: String get() = (value as? JsonPrimitive)?.contentOrNull ?: ""
    val checked: Boolean get() = (value as? JsonPrimitive)?.booleanOrNull == true
    val placeholder: String get() = configuration["placeholder"]?.jsonPrimitive?.contentOrNull ?: ""
    val hint: String get() = schema["description"]?.jsonPrimitive?.contentOrNull ?: ""
    val rows: Int get() = configuration["rows"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 4
    val displayText: String get() = configuration["text"]?.jsonPrimitive?.contentOrNull ?: ""
    val variant: String get() = configuration["variant"]?.jsonPrimitive?.contentOrNull ?: "info"
    val level: Int get() = configuration["level"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 6) ?: 3
    val tabs: List<ViewTab> get() = children.map { ViewTab(it.key, it.label) }
    val firstTab: String get() = children.firstOrNull()?.key ?: ""
    val minimum: String? get() = schema["minimum"]?.toString()
    val maximum: String? get() = schema["maximum"]?.toString()
    val step: String get() = if (schema["type"] == JsonPrimitive("integer")) "1" else "any"
    val selectionPlaceholder: String get() = placeholder.ifBlank { "Select…" }
    val selectedValues: Set<String> get() = (value as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
    val remoteCurrent: Boolean get() = control == "remote-select" && text.isNotBlank() && options.none { it.value == text }
    val accept: String get() = configuration["accept"]?.jsonPrimitive?.contentOrNull
        ?: if (control == "image-upload") "image/*" else ""
}

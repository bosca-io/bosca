package bosca.profiles.web

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun profileTextAttributeValue(
    model: ProfileEditorModel,
    typeId: String,
    value: String,
): JsonElement {
    val field = model.requireProfileAttributeField(typeId)
    require(!field.required || value.isNotBlank()) { "${field.label} is required." }
    val parsed = when (field.inputType) {
        "number" -> value.trim().toLongOrNull()?.let(::JsonPrimitive)
            ?: value.trim().toDoubleOrNull()?.let(::JsonPrimitive)
            ?: throw IllegalArgumentException("${field.label} must be a number.")
        else -> JsonPrimitive(value)
    }
    return buildJsonObject { put(field.key, parsed) }
}

internal fun profileBooleanAttributeValue(
    model: ProfileEditorModel,
    typeId: String,
    value: Boolean,
): JsonElement {
    val field = model.requireProfileAttributeField(typeId)
    require(field.boolean) { "${field.label} is not a boolean field." }
    return buildJsonObject { put(field.key, value) }
}

internal fun ProfileEditorModel.requireEditableProfileAttributeType(typeId: String): ProfileAttributeTypeRow {
    require(typeId.isNotBlank() && typeId != "__choose") { "Choose an attribute type." }
    val type = attributeTypes.firstOrNull { it.id == typeId } ?: error("Unknown attribute type")
    require(!type.protected) { "This attribute is managed by the platform." }
    return type
}

private fun ProfileEditorModel.requireProfileAttributeField(typeId: String): ProfileAttributeFieldRow {
    val type = requireEditableProfileAttributeType(typeId)
    return type.field.takeIf { it.key.isNotBlank() }
        ?: error("This attribute type has no editable form.")
}

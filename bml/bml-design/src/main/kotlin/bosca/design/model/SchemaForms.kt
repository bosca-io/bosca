package bosca.design.model

import kotlinx.serialization.json.*

/** Applies JSON Schema defaults without overwriting supplied fields, including false, zero and null. */
fun schemaFormDefaults(schema: JsonObject, value: JsonObject): JsonObject {
    val data = value.toMutableMap()
    (schema["properties"] as? JsonObject).orEmpty().forEach { (key, element) ->
        val property = element as? JsonObject ?: return@forEach
        if (!data.containsKey(key)) property["default"]?.let { data[key] = it }
        val nested = data[key] as? JsonObject
        if (nested != null) data[key] = schemaFormDefaults(property, nested)
        else if (!data.containsKey(key) && property["properties"] is JsonObject) {
            val defaults = schemaFormDefaults(property, JsonObject(emptyMap()))
            if (defaults.isNotEmpty()) data[key] = defaults
        }
    }
    return JsonObject(data)
}

/**
 * Projects the existing Forms JSON Schema/UI Schema into shared controls. Applications resolve
 * remote choices by property and map their domain control names to neutral library controls.
 */
fun schemaFormNodes(
    schema: JsonObject,
    uiSchema: JsonObject,
    value: JsonObject = JsonObject(emptyMap()),
    options: Map<String, List<SchemaFormOption>> = emptyMap(),
    controlAliases: Map<String, String> = emptyMap(),
    errors: Map<String, String> = emptyMap(),
): List<SchemaFormNode> {
    require(uiSchema["version"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported Forms UI Schema version" }
    val data = schemaFormDefaults(schema, value)
    fun nodes(layout: JsonArray, parentKey: String): List<SchemaFormNode> = layout.mapIndexed { index, item ->
        val node = item.jsonObject
        val key = "$parentKey-$index"
        val property = node["property"]?.jsonPrimitive?.contentOrNull ?: ""
        var propertySchema = schema
        var propertyValue: JsonElement? = data
        var required = false
        if (property.isNotBlank()) for (segment in property.split('.')) {
            required = (propertySchema["required"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content == segment }
            propertySchema = (propertySchema["properties"] as? JsonObject)?.get(segment) as? JsonObject
                ?: error("Form property '$property' is missing from JSON Schema")
            propertyValue = (propertyValue as? JsonObject)?.get(segment)
        }
        val control = node["control"]?.jsonPrimitive?.contentOrNull ?: ""
        SchemaFormNode(
            key = key,
            type = node.getValue("type").jsonPrimitive.content,
            label = node["label"]?.jsonPrimitive?.contentOrNull ?: property,
            property = property,
            control = controlAliases[control] ?: control,
            schema = propertySchema,
            configuration = node,
            value = propertyValue ?: propertySchema["default"] ?: JsonNull,
            required = required,
            error = errors[property] ?: "",
            columns = node["col"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 12) ?: 12,
            options = options[property] ?: (propertySchema["enum"] as? JsonArray).orEmpty().map {
                SchemaFormOption(it.jsonPrimitive.content, it.jsonPrimitive.content)
            },
            children = nodes(node["children"] as? JsonArray ?: JsonArray(emptyList()), key),
        )
    }
    return nodes(uiSchema.getValue("layout").jsonArray, "form")
}

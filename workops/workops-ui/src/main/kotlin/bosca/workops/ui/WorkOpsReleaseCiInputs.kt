package bosca.workops.ui

import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans
import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans.Inputs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Captured trigger declarations mapped to neutral form keys; original names remain API input keys. */
@Serializable
class WorkOpsReleaseCiInputs(val inputs: List<Inputs> = emptyList()) {
    private fun key(index: Int) = "input$index"
    private fun initialValue(input: Inputs): JsonElement = when (input.type) {
        "boolean" -> JsonPrimitive(input.defaultValue == "true")
        "number" -> input.defaultValue?.let { JsonPrimitive(it.toDouble()) } ?: JsonNull
        else -> JsonPrimitive(input.defaultValue ?: input.options.firstOrNull().orEmpty())
    }
    val defaults get() = buildJsonObject {
        inputs.forEachIndexed { index, input -> put(key(index), initialValue(input)) }
    }
    val schema get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            inputs.forEachIndexed { index, input -> putJsonObject(key(index)) {
                put("type", when (input.type) { "boolean" -> "boolean"; "number" -> "number"; else -> "string" })
                put("title", input.name)
                input.description?.let { put("description", it) }
                put("default", initialValue(input))
                if (input.type == "choice") put("enum", JsonArray(input.options.map(::JsonPrimitive)))
            } }
        }
        put("required", JsonArray(inputs.mapIndexedNotNull { index, input -> key(index).takeIf { input.required } }.map(::JsonPrimitive)))
    }
    val uiSchema get() = buildJsonObject {
        put("version", 1)
        put("layout", JsonArray(inputs.mapIndexed { index, input -> buildJsonObject {
            put("type", "field"); put("property", key(index)); put("label", input.name)
            put("control", when (input.type) { "boolean" -> "checkbox"; "number" -> "number-input"; "choice" -> "select"; else -> "text-input" })
        } }))
    }

    fun payload(raw: String): JsonObject {
        val values = requireNotNull(Json.parseToJsonElement(raw) as? JsonObject) { "Release inputs must be an object." }
        val fallback = defaults
        return buildJsonObject {
            inputs.forEachIndexed { index, input ->
                val value = (values[key(index)] ?: fallback[key(index)]) as? JsonPrimitive
                require(value != null && value != JsonNull) { "Enter ${input.name}." }
                val text = value.content
                require(!input.required || text.isNotBlank()) { "Enter ${input.name}." }
                when (input.type) {
                    "boolean" -> require(text == "true" || text == "false") { "${input.name} must be true or false." }
                    "number" -> require(text.toDoubleOrNull() != null) { "${input.name} must be a number." }
                    "choice" -> require(text in input.options) { "Choose an available ${input.name}." }
                }
                put(input.name, text)
            }
        }
    }

    companion object {
        fun from(plans: List<ReleasePlans>) = WorkOpsReleaseCiInputs(plans.flatMap { it.inputs }.distinctBy { it.name })
    }
}

package bosca.workops.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The existing Forms contract selected by the WorkOps project. */
@Serializable
data class WorkOpsTaskForm(val key: String, val name: String, val version: Int, val schema: JsonObject, val uiSchema: JsonObject) {
    /** Static configured sources keep the same single-load lifecycle as the Forms controls. */
    val staticChoiceKeys: String get() {
        val keys = mutableListOf<String>()
        fun collect(layout: JsonArray, parent: String) {
            for ((index, item) in layout.withIndex()) {
                val node = item.jsonObject
                val key = "$parent-$index"
                val control = node["control"]?.jsonPrimitive?.contentOrNull
                val configuration = when (control) {
                    "graphql-select" -> node["graphql"] as? JsonObject
                    "api-script-select" -> node["apiScript"] as? JsonObject
                    else -> null
                }
                val searchKey = if (control == "graphql-select") "searchVariable" else "searchParam"
                if (node["type"] == JsonPrimitive("field") && configuration != null &&
                    configuration[searchKey]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) keys += key
                (node["children"] as? JsonArray)?.let { collect(it, key) }
            }
        }
        collect(uiSchema.getValue("layout").jsonArray, "form")
        return keys.joinToString(" ")
    }
}

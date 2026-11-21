package bosca.workops.ui

import bosca.design.model.SchemaFormOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** WorkOps supplies per-task destinations to the shared schema renderer and owning close API. */
@Serializable
data class WorkOpsSprintCloseDraft(
    val sprint: WorkOpsSprintRecord,
    val tasks: List<WorkOpsSprintTask> = emptyList(),
    val futures: List<WorkOpsSprintChoice> = emptyList(),
    val destinations: Map<String, String> = emptyMap(),
    val velocity: String = "",
    val ready: Boolean = false,
) {
    val completed get() = tasks.filter { it.available && it.finished }
    val unfinished get() = tasks.filter { !it.available || !it.finished }
    val unavailableCount get() = tasks.count { !it.available }
    val choices get() = listOf(SchemaFormOption("backlog", "Backlog")) + futures.map { SchemaFormOption(it.id, it.name) }
    val options get() = unfinished.associate { task ->
        val selected = destinations[task.id]
        task.id to (choices + if (selected != null && choices.none { it.value == selected })
            listOf(SchemaFormOption(selected, "Selected sprint unavailable", disabled = true)) else emptyList())
    }
    val value get() = buildJsonObject { for (task in unfinished) put(task.id, destinations[task.id] ?: "backlog") }
    val schema get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { for (task in unfinished) putJsonObject(task.id) {
            put("type", "string"); put("default", "backlog")
            putJsonArray("enum") { choices.forEach { add(JsonPrimitive(it.value)) } }
        } }
        putJsonArray("required") { unfinished.forEach { add(JsonPrimitive(it.id)) } }
    }
    val uiSchema get() = buildJsonObject {
        put("version", 1)
        putJsonArray("layout") { unfinished.forEachIndexed { index, task -> addJsonObject {
            put("type", "field"); put("property", task.id); put("control", "select")
            put("label", if (task.available) "${task.key} · ${task.label}" else "Unavailable task ${index + 1}")
        } } }
    }
}

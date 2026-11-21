package bosca.workops.ui

import bosca.design.model.SchemaFormOption
import kotlinx.serialization.Serializable

/** Start choices retain selected tasks across offset pages and keep the inspected sprint version. */
@Serializable
data class WorkOpsSprintStartDraft(
    val sprint: WorkOpsSprintRecord,
    val startDate: String,
    val endDate: String,
    val query: String = "",
    val offset: Long = 0,
    val selectedIds: List<String> = emptyList(),
    val selectedTasks: List<WorkOpsSprintTask> = emptyList(),
    val candidates: List<WorkOpsSprintTask> = emptyList(),
    val hasNext: Boolean = false,
    val ready: Boolean = false,
) {
    val options: List<SchemaFormOption> get() {
        val known = (selectedTasks + candidates).associateBy { it.id }
        return (selectedIds + candidates.map { it.id }).distinct().mapIndexed { index, id ->
            val task = known[id]
            SchemaFormOption(id, if (task?.available == true) "${task.key} · ${task.label}" else "Unavailable selected task ${index + 1}")
        }
    }
    val unavailableCount get() = selectedIds.count { id -> selectedTasks.none { it.id == id && it.available } }
}

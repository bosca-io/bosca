package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsBoardCardFields
import kotlinx.serialization.Serializable

/** Card body values are WorkOps data; movement geometry and lifecycle belong to the Design Library. */
@Serializable
data class WorkOpsBoardCard(
    val id: String, val key: String, val summary: String, val version: Long, val projectId: String,
    val statusId: String, val statusName: String, val priority: String,
    val assigneeId: String?, val assigneeName: String?, val transitions: List<WorkOpsBoardTransition>,
) {
    val label get() = workOpsContentLabel(summary, id, "Task")
    val href get() = "/workops/tasks/${workOpsEncode(id)}"
    val assigneeLabel get() = workOpsAssigneeLabel(assigneeId, assigneeName)
    // Status/version are root attributes: changing columns must retain a pending shared move.
    val bodyKey get() = listOf(id, key, label, priority, assigneeId, assigneeLabel).joinToString("|") { "${it?.length ?: 0}:$it" }
}

internal fun IWorkOpsBoardCardFields.boardCard() = WorkOpsBoardCard(
    id, key, summary, version, project.id, status.id, status.name, priority.name, assigneeProfileId, assignee?.name,
    transitions.map { WorkOpsBoardTransition(it.id, it.name, it.toState.status.id, it.toState.status.category.name) },
)

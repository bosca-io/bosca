package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsWorkflowRecord(
    val id: String, val name: String, val description: String = "", val initialId: String = "", val version: Long = 0,
    val states: List<WorkOpsWorkflowStateRecord> = emptyList(), val transitions: List<WorkOpsWorkflowTransitionRecord> = emptyList(),
) {
    val orderedStates get() = states.sortedBy { it.order }
    fun stateName(id: String) = states.firstOrNull { it.id == id }?.name ?: "State unavailable"
    fun sourceNames(transition: WorkOpsWorkflowTransitionRecord) = if ("*" in transition.from) "Any state" else transition.from.joinToString(", ", transform = ::stateName)
}

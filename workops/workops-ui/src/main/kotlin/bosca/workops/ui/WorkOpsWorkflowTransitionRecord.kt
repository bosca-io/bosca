package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsWorkflowTransitionRecord(val id: String, val name: String, val description: String, val from: List<String>, val to: String)

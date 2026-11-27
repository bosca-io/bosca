package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsWorkflowStateRecord(val id: String, val name: String, val category: String, val color: String, val order: Int)

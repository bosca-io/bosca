package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsWorkflowSchemeRecord(val id: String, val name: String, val description: String, val defaultId: String, val version: Long)

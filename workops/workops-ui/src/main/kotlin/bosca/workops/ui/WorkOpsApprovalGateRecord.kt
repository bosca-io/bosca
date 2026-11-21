package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsApprovalGateRecord(val runId: String, val nodeId: String, val prompt: String)

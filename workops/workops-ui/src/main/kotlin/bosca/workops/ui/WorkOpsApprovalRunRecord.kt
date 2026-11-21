package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsApprovalRunRecord(val id: String, val status: String, val error: String?, val modified: String, val gates: List<WorkOpsApprovalGateRecord>)

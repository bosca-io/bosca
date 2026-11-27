package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsApprovalDraft(val runId: String = "", val nodeId: String = "", val prompt: String = "", val approved: Boolean = true, val note: String = "")

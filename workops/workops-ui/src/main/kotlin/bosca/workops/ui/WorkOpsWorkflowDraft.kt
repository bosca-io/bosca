package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Captured identity and revision survive catalog refreshes and rejected actions. */
@Serializable
data class WorkOpsWorkflowDraft(val kind: String = "workflows", val id: String = "", val name: String = "", val description: String = "", val defaultId: String = "", val version: Long = 0) {
    val label get() = if (kind == "schemes") "workflow scheme" else "workflow"
}

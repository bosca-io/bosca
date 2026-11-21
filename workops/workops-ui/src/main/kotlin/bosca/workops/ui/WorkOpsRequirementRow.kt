package bosca.workops.ui

import bosca.workops.ui.graphql.WorkOpsStatusCategory
import kotlinx.serialization.Serializable

/** Presentation shared by task-owned and specification-owned requirement tables. */
@Serializable
data class WorkOpsRequirementRow(
    val id: String,
    val metadataId: String,
    val version: Long,
    val sortOrder: Int,
    val status: String,
    val category: WorkOpsStatusCategory,
    val priority: String,
    val priorityColor: String,
    val assigneeId: String?,
    val assignee: String?,
)

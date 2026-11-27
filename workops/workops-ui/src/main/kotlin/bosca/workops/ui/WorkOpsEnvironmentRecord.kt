package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsEnvironmentFields
import kotlinx.serialization.Serializable

/** The inspected environment retains the pipeline-owned policy used by its versioned edit. */
@Serializable
data class WorkOpsEnvironmentRecord(
    val id: String,
    val key: String,
    val name: String,
    val description: String,
    val order: Int,
    val requiresApproval: Boolean,
    val autoPromote: Boolean,
    val typeId: String,
    val sources: List<String>,
    val version: Long,
    val permissions: List<WorkOpsEnvironmentGrant> = emptyList(),
    val targetType: String? = null,
)

internal fun IWorkOpsEnvironmentFields.environmentRecord() = WorkOpsEnvironmentRecord(id, key, name,
    description.orEmpty(), displayOrder, requiresApproval, autoPromote, typeId, promotionSourceIds, version,
    permissions.map { WorkOpsEnvironmentGrant(it.groupId, it.action.name) })

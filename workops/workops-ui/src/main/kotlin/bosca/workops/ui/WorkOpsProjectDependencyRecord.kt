package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsDependencyFields
import kotlinx.serialization.Serializable

/** A declaration's stored identities remain separate from authorized project labels. */
@Serializable
data class WorkOpsProjectDependencyRecord(
    val id: String,
    val consumerId: String,
    val providerId: String,
    val constraint: String,
    val type: String,
    val status: String,
    val version: Long,
) {
    val tone get() = when (status) { "CURRENT" -> "success"; "OUTDATED" -> "warning"; else -> "error" }
}

internal fun IWorkOpsDependencyFields.projectDependency() = WorkOpsProjectDependencyRecord(id, consumerProjectId,
    providerProjectId, providerVersionConstraint, dependencyType.name, status.name, version)

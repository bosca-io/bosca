package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsEnvironmentTypeFields
import kotlinx.serialization.Serializable

/** A catalog type retains its inspected revision independently from later choice reloads. */
@Serializable
data class WorkOpsEnvironmentTypeRecord(
    val id: String,
    val name: String,
    val description: String = "",
    val order: Int = 0,
    val version: Long = 0,
)

internal fun IWorkOpsEnvironmentTypeFields.environmentTypeRecord() =
    WorkOpsEnvironmentTypeRecord(id, name, description.orEmpty(), displayOrder, version)

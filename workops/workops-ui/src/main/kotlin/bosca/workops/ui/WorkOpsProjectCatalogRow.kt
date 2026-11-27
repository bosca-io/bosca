package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsComponentFields
import bosca.workops.ui.graphql.IWorkOpsLabelFields
import bosca.workops.ui.graphql.IWorkOpsVersionFields
import kotlinx.serialization.Serializable

/** Project catalog presentation; writes still use each catalog's existing typed API. */
@Serializable
data class WorkOpsProjectCatalogRow(
    val id: String,
    val name: String,
    val description: String = "",
    val version: Long = 0,
    val released: Boolean = false,
    val archived: Boolean = false,
    val date: String = "",
    val mode: String = "",
    val color: String = "",
    val startDate: String = "",
    val sequenceNumber: Int = 0,
    val leadProfileId: String? = null,
    val defaultAssigneeProfileId: String? = null,
) {
    val modeLabel get() = mode.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
    val state get() = when { released -> "Released"; archived -> "Archived"; else -> "Unreleased" }
}

internal fun IWorkOpsVersionFields.projectCatalogRow() = WorkOpsProjectCatalogRow(id, name, description.orEmpty(), version,
    released, archived, releaseDate.orEmpty(), startDate = startDate.orEmpty(), sequenceNumber = sequenceNumber)
internal fun IWorkOpsComponentFields.projectCatalogRow() = WorkOpsProjectCatalogRow(id, name, description.orEmpty(), version, mode = assigneeMode.name, leadProfileId = leadProfileId, defaultAssigneeProfileId = defaultAssigneeProfileId)
internal fun IWorkOpsLabelFields.projectCatalogRow() = WorkOpsProjectCatalogRow(id, name, version = version, color = colorHex.orEmpty())

package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A just-created version remains selectable if refreshing that project's versions fails. */
@Serializable
data class WorkOpsReleaseVersionChoice(val projectId: String, val item: WorkOpsProjectCatalogRow)

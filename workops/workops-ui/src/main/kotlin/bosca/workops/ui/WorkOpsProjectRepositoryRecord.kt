package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A stored association can be managed independently of Git's repository-label visibility. */
@Serializable
data class WorkOpsProjectRepositoryRecord(
    val id: String,
    val repositoryId: String,
    val name: String = "",
    val slug: String = "",
    val archived: Boolean = false,
    val available: Boolean = true,
) {
    val label get() = if (available) name else "Repository unavailable"
    val href get() = if (available) "/repositories/${workOpsEncode(repositoryId)}" else ""
}

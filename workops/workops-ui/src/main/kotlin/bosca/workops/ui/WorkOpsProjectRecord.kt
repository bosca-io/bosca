package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsProjectFields
import kotlinx.serialization.Serializable

/** Project facts retain the owning API's version and separate private owner/program labels. */
@Serializable
data class WorkOpsProjectRecord(
    val id: String,
    val key: String,
    val name: String,
    val description: String,
    val ownerId: String,
    val ownerName: String,
    val programId: String,
    val archived: Boolean,
    val created: String,
    val modified: String,
    val version: Long,
) {
    val ownerLabel get() = ownerName.ifBlank { "Profile unavailable" }
    val href get() = "/workops/projects/${workOpsEncode(id)}"
}

internal fun IWorkOpsProjectFields.projectRecord() = WorkOpsProjectRecord(id, key, name, description.orEmpty(),
    ownerProfileId, owner?.name.orEmpty(), program.id, archivedAt != null, createdAt, modifiedAt, version)

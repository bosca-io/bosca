package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A planning list row also preserves the inspected fields needed for unrelated versioned edits. */
@Serializable
data class WorkOpsPlanningRow(
    val id: String = "",
    val key: String = "",
    val name: String = "",
    val description: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val parentId: String = "",
    val parentName: String = "",
    val archived: Boolean = false,
    val modified: String = "",
    val version: Long = 0,
    val programCount: Int = 0,
    val startDate: String? = null,
    val targetDate: String? = null,
    val defaultTaskTypeSchemeId: String? = null,
    val taskCreationFormSchemaKey: String = "workops.create-task",
    val originalOwnerId: String = ownerId,
) {
    val ownerLabel get() = workOpsAssigneeLabel(ownerId, ownerName)
}

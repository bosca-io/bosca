package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Independent edit values and the version from which the user started editing. */
@Serializable
data class WorkOpsTaskDraft(
    val summary: String = "",
    val description: String = "",
    val priorityId: String = "",
    val assigneeId: String = "",
    val sprintId: String = "",
    val dueDate: String = "",
    val originalEstimate: String = "",
    val remainingEstimate: String = "",
    val expectedVersion: Long = 0,
)

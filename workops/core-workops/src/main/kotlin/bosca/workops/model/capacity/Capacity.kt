package bosca.workops.model.capacity

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * R20 — capacity declared per (assignee, sprint). The reporter
 * compares this against the sum of the assignee's task estimates
 * inside the sprint and surfaces over-commit warnings.
 */
@Serializable
data class Capacity(
    @ColumnName("sprint_id")
    @Contextual
    val sprintId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("committed_seconds")
    val committedSeconds: Long,
    val notes: String? = null,
)

/** Aggregated reporter view — committed vs delivered per assignee. */
@Serializable
data class CapacityReportEntry(
    @Contextual val profileId: UUID,
    val committedSeconds: Long,
    val plannedSeconds: Long,
    val completedSeconds: Long,
    val overCommitSeconds: Long,
)

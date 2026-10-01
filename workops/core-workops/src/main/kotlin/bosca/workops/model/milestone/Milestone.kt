package bosca.workops.model.milestone

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@DbMapper(MilestoneStateMapper::class)
@Serializable
enum class MilestoneState { OPEN, CLOSED }

object MilestoneStateMapper : EnumMapper<MilestoneState>({ MilestoneState.valueOf(it.uppercase()) })

/**
 * A program-level cross-project commitment (R9). Milestones live at
 * the program level, **not** the project level — they are the unit
 * by which delivery leaders bundle work across teams.
 *
 * Tasks attach via `Task.milestoneId?` (added in this phase's
 * migration). Phase 9's roadmap (R18) renders milestones as Gantt
 * markers across the program timeline.
 */
@BatchKey("id")
@Serializable
data class Milestone(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("target_date")
    @Contextual
    val targetDate: OffsetDateTime? = null,
    val state: MilestoneState = MilestoneState.OPEN,
    @ColumnName("closed_at")
    @Contextual
    val closedAt: OffsetDateTime? = null,
    val version: Long = 0,
)

/** Input for creating a [Milestone]. */
@Serializable
data class CreateMilestoneInput(
    @Contextual
    val programId: UUID,
    val name: String,
    val description: String? = null,
    @Contextual
    val targetDate: OffsetDateTime? = null,
)

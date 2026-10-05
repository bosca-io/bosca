package bosca.workops.model.sla

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Per-goal lifecycle outcome stored on `workops.task_sla_state.outcome`.
 * `OPEN` is the initial value; the tick job flips it to one of the
 * three terminal states.
 */
@Serializable
enum class SlaOutcome {
    OPEN,
    MET,
    BREACHED,
    STOPPED_EARLY,
}

/**
 * R13 — a working-hours calendar drives `due_at` arithmetic. Stored
 * as JSONB blobs (`weeklyHours`, `holidays`) so adding new days /
 * holiday entries doesn't require a migration.
 */
@Serializable
data class WorkingCalendar(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("time_zone")
    val timeZone: String,
    /**
     * `Map<DayOfWeek, List<{startLocal, endLocal}>>` — day name in
     * uppercase (`MONDAY`, `TUESDAY`, ...). Each entry is an
     * `HH:MM` 24-hour pair.
     */
    @ColumnName("weekly_hours")
    @Contextual
    val weeklyHours: JsonElement = JsonObject(emptyMap()),
    /**
     * `List<String>` of `YYYY-MM-DD` ISO dates excluded from working
     * hours regardless of weekday rules.
     */
    @Contextual
    val holidays: JsonElement = JsonObject(emptyMap()),
    val version: Long = 0,
)

/**
 * R13 — `SlaPolicy` carries one or more `SlaGoal`s. The goals are
 * stored as a sibling table (`workops.sla_goal`) with FK on
 * `policy_id`; the start / pause / stop predicates are BQL strings
 * the evaluator parses lazily.
 */
@Serializable
data class SlaPolicy(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    val version: Long = 0,
)

@Serializable
data class SlaGoal(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("policy_id")
    @Contextual
    val policyId: UUID,
    val name: String,
    @ColumnName("start_conditions")
    val startConditions: String,
    @ColumnName("pause_conditions")
    val pauseConditions: String? = null,
    @ColumnName("stop_conditions")
    val stopConditions: String,
    @ColumnName("target_minutes")
    val targetMinutes: Int,
    @ColumnName("at_risk_at_percent")
    val atRiskAtPercent: Int = 80,
    @ColumnName("calendar_id")
    @Contextual
    val calendarId: UUID? = null,
    @ColumnName("display_order")
    val displayOrder: Int = 0,
)

/**
 * Per-task per-goal tracker. The tick job holds three timestamps —
 * `started_at`, `paused_total_seconds`, `due_at` — plus the
 * outcome and two debounce flags so at-risk / breach events fire
 * exactly once per crossing.
 */
@Serializable
data class TaskSlaState(
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("goal_id")
    @Contextual
    val goalId: UUID,
    @ColumnName("started_at")
    @Contextual
    val startedAt: OffsetDateTime,
    @ColumnName("paused_at")
    @Contextual
    val pausedAt: OffsetDateTime? = null,
    @ColumnName("paused_total_seconds")
    val pausedTotalSeconds: Long = 0,
    @ColumnName("due_at")
    @Contextual
    val dueAt: OffsetDateTime,
    val outcome: SlaOutcome = SlaOutcome.OPEN,
    @ColumnName("at_risk_emitted")
    val atRiskEmitted: Boolean = false,
    @ColumnName("breach_emitted")
    val breachEmitted: Boolean = false,
    val version: Long = 0,
)

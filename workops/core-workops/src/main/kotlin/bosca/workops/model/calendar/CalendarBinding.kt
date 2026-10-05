package bosca.workops.model.calendar

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray

/**
 * R31 — what a Work Ops calendar represents in `core-calendar`'s
 * Calendar surface. The four kinds compose:
 *
 *  - `WORKOPS_PROJECT_CALENDAR` — auto-managed per project.
 *  - `WORKOPS_PROGRAM_CALENDAR` — auto-managed per program.
 *  - `WORKOPS_PORTFOLIO_CALENDAR` — auto-managed per portfolio.
 *  - `WORKING_HOURS` — repoints SLA's working calendar onto
 *    `core-calendar` (Phase 17.2 unifies the storage).
 */
@Serializable
enum class CalendarBindingKind {
    WORKOPS_PROJECT_CALENDAR,
    WORKOPS_PROGRAM_CALENDAR,
    WORKOPS_PORTFOLIO_CALENDAR,
    WORKING_HOURS,
}

/**
 * R31 — what a single binding row references on the Work Ops side.
 * The dispatcher reads / writes binding rows so a Task can answer
 * "which calendar events represent me?" in O(1).
 */
@Serializable
enum class CalendarEventBindingKind {
    @kotlinx.serialization.SerialName("workops.task.due")
    TASK_DUE,
    @kotlinx.serialization.SerialName("workops.task.span")
    TASK_SPAN,
    @kotlinx.serialization.SerialName("workops.sla.deadline")
    SLA_DEADLINE,
    @kotlinx.serialization.SerialName("workops.sprint")
    SPRINT,
    @kotlinx.serialization.SerialName("workops.milestone")
    MILESTONE,
    @kotlinx.serialization.SerialName("workops.release")
    RELEASE,
    @kotlinx.serialization.SerialName("workops.version_release")
    VERSION_RELEASE,
    @kotlinx.serialization.SerialName("workops.ceremony")
    CEREMONY,
    @kotlinx.serialization.SerialName("workops.transition_meeting")
    TRANSITION_MEETING,
}

@Serializable
data class CalendarBinding(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("entity_kind")
    val entityKind: String,
    @ColumnName("entity_id")
    @Contextual
    val entityId: UUID,
    @ColumnName("calendar_event_id")
    @Contextual
    val calendarEventId: UUID,
    val kind: String,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * R31 — RecurringCeremony. The recurrenceRule is a standard
 * RFC 5545 RRULE string (e.g. `FREQ=WEEKLY;BYDAY=MO,WE,FR`).
 * The participants list is opaque to the workops surface — it's
 * forwarded to `core-calendar` on event creation.
 */
@Serializable
data class RecurringCeremony(
    @Contextual
    val id: UUID = UUID.NIL,
    val scope: String,
    @ColumnName("scope_id")
    @Contextual
    val scopeId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("recurrence_rule")
    val recurrenceRule: String,
    @ColumnName("duration_minutes")
    val durationMinutes: Int,
    @Contextual
    val participants: JsonElement = JsonArray(emptyList()),
    @ColumnName("created_by_profile_id")
    @Contextual
    val createdByProfileId: UUID,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("archived_at")
    @Contextual
    val archivedAt: OffsetDateTime? = null,
    val version: Long = 0,
)

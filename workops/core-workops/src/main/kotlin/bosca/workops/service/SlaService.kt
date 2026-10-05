package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaPolicy
import bosca.workops.model.sla.WorkingCalendar

/** Look-up + admin CRUD for working calendars. */
interface WorkingCalendarService : Service {
    suspend fun getById(id: UUID): WorkingCalendar?
    suspend fun list(): List<WorkingCalendar>
    suspend fun create(input: WorkingCalendarInput): WorkingCalendar
}

data class WorkingCalendarInput(
    val name: String,
    val description: String?,
    val timeZone: String,
    val weeklyHours: String,
    val holidays: String,
)

/** Policy + goal reads. Goals are loaded on demand. */
interface SlaPolicyService : Service {
    suspend fun getById(id: UUID): SlaPolicy?
    suspend fun list(): List<SlaPolicy>
    suspend fun create(name: String, description: String?): SlaPolicy
    suspend fun goalsFor(policyId: UUID): List<SlaGoal>
    suspend fun addGoal(goal: SlaGoal): SlaGoal
}

/**
 * Tick the SLA queue: emit at-risk / breach events for every
 * boundary crossing inside the lookahead window and flip outcomes
 * accordingly. Idempotent against the debounce flags.
 */
interface SlaTickService : Service {
    /**
     * Walks open SLAs whose at-risk or breach boundary lands
     * inside `[now, now + atRiskWindowMinutes minutes]`. Returns
     * the count of events emitted so the calling job can log it.
     */
    suspend fun tick(now: OffsetDateTime, atRiskWindowMinutes: Int = 15, limit: Int = 256): Int
}

package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.calendar.CalendarBinding
import bosca.workops.model.calendar.CalendarEventBindingKind
import bosca.workops.model.calendar.RecurringCeremony
import kotlinx.serialization.json.JsonElement

/**
 * R31 — bridge between Work Ops domain rows and `core-calendar`
 * events. Phase 17 introduces the binding table and the service
 * surface; the actual `CalendarEvent` upsert into `core-calendar`
 * is wired by the `WorkOpsCalendarSyncService` event listener
 * that follows when core-calendar's domain bus is plumbed.
 *
 * Calling code that knows the (Work Ops entity, calendar event)
 * pair recordable via `bind`; on entity deletion, calling code
 * unwinds via `unbind`.
 */
interface CalendarBindingService : Service {
    suspend fun listForEntity(entityKind: String, entityId: UUID): List<CalendarBinding>
    suspend fun listForEvent(calendarEventId: UUID): List<CalendarBinding>
    suspend fun bind(
        entityKind: String,
        entityId: UUID,
        calendarEventId: UUID,
        kind: CalendarEventBindingKind,
    ): CalendarBinding?
    suspend fun unbind(entityKind: String, entityId: UUID, kind: CalendarEventBindingKind)
    suspend fun unbindEvent(calendarEventId: UUID)
}

interface RecurringCeremonyService : Service {
    suspend fun listForScope(scope: String, scopeId: UUID): List<RecurringCeremony>
    suspend fun getById(id: UUID): RecurringCeremony?
    suspend fun create(
        scope: String,
        scopeId: UUID,
        name: String,
        description: String?,
        recurrenceRule: String,
        durationMinutes: Int,
        participants: JsonElement,
        createdByProfileId: UUID,
    ): RecurringCeremony
    suspend fun archive(id: UUID): RecurringCeremony
}

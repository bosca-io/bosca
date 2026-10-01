package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.calendar.CalendarBinding
import bosca.workops.model.calendar.CalendarEventBindingKind
import bosca.workops.model.calendar.RecurringCeremony
import bosca.workops.repository.CalendarBindingRepository
import bosca.workops.repository.RecurringCeremonyInsertParams
import bosca.workops.repository.RecurringCeremonyRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class CalendarBindingServiceImpl(
    private val repository: CalendarBindingRepository,
    private val json: Json,
) : CalendarBindingService {

    override suspend fun listForEntity(entityKind: String, entityId: UUID) =
        repository.listForEntity(entityKind, entityId)

    override suspend fun listForEvent(calendarEventId: UUID) =
        repository.listForEvent(calendarEventId)

    override suspend fun bind(
        entityKind: String,
        entityId: UUID,
        calendarEventId: UUID,
        kind: CalendarEventBindingKind,
    ): CalendarBinding? = repository.add(entityKind, entityId, calendarEventId, serialName(kind))

    override suspend fun unbind(entityKind: String, entityId: UUID, kind: CalendarEventBindingKind) =
        repository.removeByEntityKind(entityKind, entityId, serialName(kind))

    override suspend fun unbindEvent(calendarEventId: UUID) =
        repository.removeByEvent(calendarEventId)

    private fun serialName(kind: CalendarEventBindingKind): String = when (kind) {
        CalendarEventBindingKind.TASK_DUE -> "workops.task.due"
        CalendarEventBindingKind.TASK_SPAN -> "workops.task.span"
        CalendarEventBindingKind.SLA_DEADLINE -> "workops.sla.deadline"
        CalendarEventBindingKind.SPRINT -> "workops.sprint"
        CalendarEventBindingKind.MILESTONE -> "workops.milestone"
        CalendarEventBindingKind.RELEASE -> "workops.release"
        CalendarEventBindingKind.VERSION_RELEASE -> "workops.version_release"
        CalendarEventBindingKind.CEREMONY -> "workops.ceremony"
        CalendarEventBindingKind.TRANSITION_MEETING -> "workops.transition_meeting"
    }
}

@ServiceImplementation
class RecurringCeremonyServiceImpl(
    private val repository: RecurringCeremonyRepository,
    private val json: Json,
) : RecurringCeremonyService {

    override suspend fun listForScope(scope: String, scopeId: UUID) =
        repository.listForScope(scope, scopeId)

    override suspend fun getById(id: UUID) = repository.getById(id)

    override suspend fun create(
        scope: String,
        scopeId: UUID,
        name: String,
        description: String?,
        recurrenceRule: String,
        durationMinutes: Int,
        participants: JsonElement,
        createdByProfileId: UUID,
    ): RecurringCeremony = repository.add(
        RecurringCeremonyInsertParams(
            scope = scope,
            scopeId = scopeId,
            name = name,
            description = description,
            recurrenceRule = recurrenceRule,
            durationMinutes = durationMinutes,
            participants = json.encodeToString(JsonElement.serializer(), participants),
            createdByProfileId = createdByProfileId,
        )
    )

    override suspend fun archive(id: UUID): RecurringCeremony =
        repository.archive(id)
            ?: throw WorkOpsNotFoundException("RecurringCeremony", id.toString())
}

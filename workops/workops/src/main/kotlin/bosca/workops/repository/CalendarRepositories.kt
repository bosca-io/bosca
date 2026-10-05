package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.calendar.CalendarBinding
import bosca.workops.model.calendar.RecurringCeremony

@Repository
interface CalendarBindingRepository {

    @Query(
        """
        select * from workops.calendar_binding
        where entity_kind = :entityKind and entity_id = :entityId
        order by created_at desc
        """
    )
    suspend fun listForEntity(entityKind: String, entityId: UUID): List<CalendarBinding>

    @Query(
        """
        select * from workops.calendar_binding
        where calendar_event_id = :calendarEventId
        """
    )
    suspend fun listForEvent(calendarEventId: UUID): List<CalendarBinding>

    @Query(
        """
        insert into workops.calendar_binding
            (entity_kind, entity_id, calendar_event_id, kind)
        values (:entityKind, :entityId, :calendarEventId, :kind)
        on conflict (entity_kind, entity_id, kind, calendar_event_id) do nothing
        returning *
        """
    )
    suspend fun add(
        entityKind: String,
        entityId: UUID,
        calendarEventId: UUID,
        kind: String,
    ): CalendarBinding?

    @Query(
        """
        delete from workops.calendar_binding
        where entity_kind = :entityKind and entity_id = :entityId and kind = :kind
        """
    )
    suspend fun removeByEntityKind(entityKind: String, entityId: UUID, kind: String)

    @Query("delete from workops.calendar_binding where calendar_event_id = :calendarEventId")
    suspend fun removeByEvent(calendarEventId: UUID)
}

@Repository
interface RecurringCeremonyRepository {

    @Query("select * from workops.recurring_ceremony where id = :id")
    suspend fun getById(id: UUID): RecurringCeremony?

    @Query(
        """
        select * from workops.recurring_ceremony
        where scope = :scope and scope_id = :scopeId
              and archived_at is null
        order by name
        """
    )
    suspend fun listForScope(scope: String, scopeId: UUID): List<RecurringCeremony>

    @Query(
        """
        insert into workops.recurring_ceremony
            (scope, scope_id, name, description, recurrence_rule,
             duration_minutes, participants, created_by_profile_id)
        values
            (:scope, :scopeId, :name, :description, :recurrenceRule,
             :durationMinutes, cast(:participants as jsonb),
             :createdByProfileId)
        returning *
        """
    )
    suspend fun add(input: RecurringCeremonyInsertParams): RecurringCeremony

    @Query(
        """
        update workops.recurring_ceremony
        set archived_at = now(), version = version + 1
        where id = :id and archived_at is null
        returning *
        """
    )
    suspend fun archive(id: UUID): RecurringCeremony?
}

data class RecurringCeremonyInsertParams(
    val scope: String,
    val scopeId: UUID,
    val name: String,
    val description: String?,
    val recurrenceRule: String,
    val durationMinutes: Int,
    val participants: String,
    val createdByProfileId: UUID,
)

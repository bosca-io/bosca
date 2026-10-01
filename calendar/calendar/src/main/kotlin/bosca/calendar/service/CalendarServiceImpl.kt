package bosca.calendar.service

import bosca.calendar.model.Calendar
import bosca.calendar.model.CalendarEvent
import bosca.calendar.model.CalendarEventInput
import bosca.calendar.model.CalendarInput
import bosca.calendar.model.EventAttachment
import bosca.calendar.model.EventAttachmentInput
import bosca.calendar.model.EventOccurrence
import bosca.calendar.model.EventParticipant
import bosca.calendar.model.EventParticipantInput
import bosca.calendar.model.OccurrenceInput
import bosca.calendar.repository.CalendarEventRepository
import bosca.calendar.repository.CalendarRepository
import bosca.calendar.repository.EventAttachmentRepository
import bosca.calendar.repository.EventParticipantRepository
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.transaction
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.time.temporal.ChronoUnit

@ServiceImplementation
class CalendarServiceImpl(
    private val calendarRepository: CalendarRepository,
    private val eventRepository: CalendarEventRepository,
    private val participantRepository: EventParticipantRepository,
    private val attachmentRepository: EventAttachmentRepository,
    private val metadataService: MetadataService,
    private val metadataPermissions: MetadataPermissionEvaluator
) : CalendarService {

    private suspend fun loadVisibleMetadata(
        authentication: AuthenticationContext?,
        metadataId: UUID,
        version: Int
    ): Metadata? {
        val metadata = metadataService.getById(metadataId, version) ?: return null
        if (!metadataPermissions.isAllowed(authentication, metadata, PermissionAction.VIEW)) return null
        return metadata
    }

    private suspend fun loadEditableMetadata(
        authentication: AuthenticationContext,
        metadataId: UUID,
        version: Int
    ): Metadata {
        val metadata = metadataService.getById(metadataId, version)
            ?: error("Metadata not found: $metadataId ($version)")
        metadataPermissions.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return metadata
    }

    override suspend fun getAllCalendars(authentication: AuthenticationContext?): List<Calendar> {
        val all = calendarRepository.getAll()
        if (all.isEmpty()) return emptyList()
        return all.filter { loadVisibleMetadata(authentication, it.metadataId, it.version) != null }
    }

    override suspend fun getCalendar(
        authentication: AuthenticationContext?,
        metadataId: UUID,
        version: Int
    ): Calendar? {
        loadVisibleMetadata(authentication, metadataId, version) ?: return null
        return calendarRepository.getById(metadataId, version)
    }

    override suspend fun createCalendar(
        authentication: AuthenticationContext,
        metadataId: UUID,
        version: Int,
        input: CalendarInput
    ): Calendar = transaction {
        validateCalendarInput(input)
        loadEditableMetadata(authentication, metadataId, version)
        calendarRepository.add(metadataId, version, input.color, input.description)
            ?: calendarRepository.getById(metadataId, version)
            ?: error("Failed to create calendar for metadata $metadataId ($version)")
    }

    override suspend fun editCalendar(
        authentication: AuthenticationContext,
        metadataId: UUID,
        version: Int,
        input: CalendarInput
    ): Calendar {
        validateCalendarInput(input)
        loadEditableMetadata(authentication, metadataId, version)
        return calendarRepository.update(metadataId, version, input.color, input.description)
    }

    override suspend fun getOccurrences(
        authentication: AuthenticationContext?,
        metadataId: UUID,
        version: Int,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<EventOccurrence> {
        require(!to.isBefore(from)) { "to must not be before from" }
        loadVisibleMetadata(authentication, metadataId, version) ?: return emptyList()
        val candidates = eventRepository.getCandidatesForRange(metadataId, version, from, to)
        return expand(candidates, from, to)
    }

    override suspend fun getOccurrencesForCalendars(
        authentication: AuthenticationContext?,
        calendars: List<Pair<UUID, Int>>,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<EventOccurrence> {
        require(!to.isBefore(from)) { "to must not be before from" }
        if (calendars.isEmpty()) return emptyList()
        // The repository layer can't take a List<UUID> alongside other params
        // (KSP rejects mixing collections with non-primitive params in @Query),
        // so we iterate per-calendar. This also lets us drop calendars the
        // caller cannot VIEW from the same loop.
        val candidates = mutableListOf<CalendarEvent>()
        for ((id, version) in calendars) {
            if (loadVisibleMetadata(authentication, id, version) == null) continue
            candidates += eventRepository.getCandidatesForRange(id, version, from, to)
        }
        if (candidates.isEmpty()) return emptyList()
        return expand(candidates, from, to)
    }

    override suspend fun getEvent(authentication: AuthenticationContext?, id: UUID): CalendarEvent? {
        val event = eventRepository.getById(id) ?: return null
        loadVisibleMetadata(authentication, event.metadataId, event.version) ?: return null
        return event
    }

    override suspend fun addEvent(
        authentication: AuthenticationContext,
        input: CalendarEventInput
    ): CalendarEvent = transaction {
        validateEventInput(input)
        loadEditableMetadata(authentication, input.metadataId, input.version)
        val parent = calendarRepository.getById(input.metadataId, input.version)
            ?: error("Metadata is not a calendar: ${input.metadataId} (${input.version})")
        eventRepository.add(
            metadataId = parent.metadataId,
            version = parent.version,
            title = input.title,
            description = input.description,
            location = input.location,
            allDay = input.allDay,
            startsAt = input.startsAt,
            endsAt = input.endsAt,
            rrule = input.rrule?.takeIf { it.isNotBlank() }
        )
    }

    override suspend fun editEvent(
        authentication: AuthenticationContext,
        id: UUID,
        input: CalendarEventInput
    ): CalendarEvent = transaction {
        validateEventInput(input)
        val existing = eventRepository.getById(id) ?: error("Event not found: $id")
        require(existing.metadataId == input.metadataId && existing.version == input.version) {
            "An event cannot be moved to a different calendar"
        }
        loadEditableMetadata(authentication, existing.metadataId, existing.version)
        require(existing.originalEventId == null) {
            "Use editOccurrence to update an override; edit the master to change every instance"
        }
        val nextRrule = input.rrule?.takeIf { it.isNotBlank() }
        val updated = eventRepository.update(
            id = id,
            title = input.title,
            description = input.description,
            location = input.location,
            allDay = input.allDay,
            startsAt = input.startsAt,
            endsAt = input.endsAt,
            rrule = nextRrule
        )
        // If the recurrence rule or seed start changed (or recurrence was removed),
        // any existing override whose recurrence_id is no longer produced by the
        // new master is now an orphan. Prune it so future expansions stay
        // consistent. When recurrence is removed entirely, every override goes.
        val rruleChanged = existing.rrule != nextRrule
        val startChanged = existing.startsAt != updated.startsAt
        if (rruleChanged || startChanged) {
            val overrides = eventRepository.getOverridesByMasterIds(listOf(id))
            for (override in overrides) {
                val rid = override.recurrenceId ?: continue
                if (nextRrule == null || !RecurrenceExpander.isOccurrenceOf(updated, rid)) {
                    eventRepository.deleteById(override.id)
                }
            }
            // EXDATEs are tied to the old occurrence times; drop any that no longer
            // correspond to a generated occurrence.
            if (nextRrule == null) {
                eventRepository.setExdates(id, null)
            } else {
                val keptExdates = RecurrenceExpander.exdatesMatching(updated.exdates) {
                    RecurrenceExpander.isOccurrenceOf(updated, it)
                }
                eventRepository.setExdates(id, keptExdates)
            }
        }
        updated
    }

    override suspend fun deleteEvent(authentication: AuthenticationContext, id: UUID) {
        val existing = eventRepository.getById(id) ?: return
        loadEditableMetadata(authentication, existing.metadataId, existing.version)
        eventRepository.deleteById(id)
    }

    override suspend fun editOccurrence(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime,
        input: OccurrenceInput
    ): CalendarEvent = transaction {
        require(input.title.isNotBlank()) { "Event title must not be blank" }
        require(!input.endsAt.isBefore(input.startsAt)) { "endsAt must not be before startsAt" }
        val master = eventRepository.getById(masterId) ?: error("Event not found: $masterId")
        require(master.rrule != null) { "Cannot edit an occurrence of a non-recurring event" }
        loadEditableMetadata(authentication, master.metadataId, master.version)
        require(RecurrenceExpander.isOccurrenceOf(master, recurrenceId)) {
            "recurrenceId $recurrenceId is not an occurrence of master $masterId"
        }
        eventRepository.upsertOverride(
            metadataId = master.metadataId,
            version = master.version,
            title = input.title,
            description = input.description,
            location = input.location,
            allDay = input.allDay,
            startsAt = input.startsAt,
            endsAt = input.endsAt,
            originalEventId = master.id,
            recurrenceId = recurrenceId
        )
    }

    override suspend fun cancelOccurrence(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime
    ): Unit = transaction {
        val master = eventRepository.getById(masterId) ?: error("Event not found: $masterId")
        require(master.rrule != null) { "Cannot cancel an occurrence of a non-recurring event" }
        loadEditableMetadata(authentication, master.metadataId, master.version)
        require(RecurrenceExpander.isOccurrenceOf(master, recurrenceId)) {
            "recurrenceId $recurrenceId is not an occurrence of master $masterId"
        }
        // Drop any override that points at this recurrence_id…
        eventRepository.getOverride(masterId, recurrenceId)?.let { eventRepository.deleteById(it.id) }
        // …and append the EXDATE so future expansions skip it.
        val nextExdates = RecurrenceExpander.addExdate(master.exdates, recurrenceId)
        eventRepository.setExdates(masterId, nextExdates)
    }

    override suspend fun splitSeriesAt(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime,
        input: CalendarEventInput
    ): CalendarEvent = transaction {
        validateEventInput(input)
        val master = eventRepository.getById(masterId) ?: error("Event not found: $masterId")
        val rrule = master.rrule ?: error("Cannot split a non-recurring event")
        require(master.metadataId == input.metadataId && master.version == input.version) {
            "Split master must stay on the same calendar"
        }
        loadEditableMetadata(authentication, master.metadataId, master.version)
        require(RecurrenceExpander.isOccurrenceOf(master, recurrenceId)) {
            "recurrenceId $recurrenceId is not an occurrence of master $masterId"
        }

        // End the original master one second before the split point.
        val cap = recurrenceId.minusSeconds(1)
        eventRepository.updateRrule(masterId, RecurrenceExpander.withUntil(rrule, cap))

        // Drop overrides at or after the split, plus any EXDATEs in that window.
        val survivingExdates = master.exdates.let { RecurrenceExpander.exdatesBefore(it, recurrenceId) }
        eventRepository.setExdates(masterId, survivingExdates)
        val overrides = eventRepository.getOverridesByMasterIds(listOf(masterId))
        for (override in overrides) {
            val rid = override.recurrenceId ?: continue
            if (!rid.isBefore(recurrenceId)) {
                eventRepository.deleteById(override.id)
            }
        }

        // Create the successor master from the split point with the new properties.
        eventRepository.add(
            metadataId = master.metadataId,
            version = master.version,
            title = input.title,
            description = input.description,
            location = input.location,
            allDay = input.allDay,
            startsAt = input.startsAt,
            endsAt = input.endsAt,
            rrule = input.rrule?.takeIf { it.isNotBlank() } ?: rrule
        )
    }

    override suspend fun endSeriesAt(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime
    ): CalendarEvent = transaction {
        val master = eventRepository.getById(masterId) ?: error("Event not found: $masterId")
        val rrule = master.rrule ?: error("Cannot end a non-recurring event")
        loadEditableMetadata(authentication, master.metadataId, master.version)
        require(RecurrenceExpander.isOccurrenceOf(master, recurrenceId)) {
            "recurrenceId $recurrenceId is not an occurrence of master $masterId"
        }
        val cap = recurrenceId.minusSeconds(1)
        val updated = eventRepository.updateRrule(masterId, RecurrenceExpander.withUntil(rrule, cap))
        // Remove now-orphan overrides and EXDATEs at or after the cut.
        val survivingExdates = master.exdates.let { RecurrenceExpander.exdatesBefore(it, recurrenceId) }
        eventRepository.setExdates(masterId, survivingExdates)
        for (override in eventRepository.getOverridesByMasterIds(listOf(masterId))) {
            val rid = override.recurrenceId ?: continue
            if (!rid.isBefore(recurrenceId)) eventRepository.deleteById(override.id)
        }
        updated
    }

    /**
     * Turns a flat list of stored rows into the user-visible occurrences.
     * Singles pass through; masters expand with EXDATEs and overrides
     * applied; overrides whose own time falls in the range are added with
     * their recurrence_id preserved for "moved from" UI hints.
     */
    private suspend fun expand(
        candidates: List<CalendarEvent>,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<EventOccurrence> {
        if (candidates.isEmpty()) return emptyList()
        val singles = candidates.filter { it.rrule == null && it.originalEventId == null }
        val masters = candidates.filter { it.rrule != null && it.originalEventId == null }
        val overrides = if (masters.isEmpty()) emptyList()
        else eventRepository.getOverridesByMasterIds(masters.map { it.id })
        val overridesByMaster = overrides.groupBy { it.originalEventId!! }

        val result = mutableListOf<EventOccurrence>()
        for (single in singles) {
            result += EventOccurrence(
                event = single,
                startsAt = single.startsAt,
                endsAt = single.endsAt
            )
        }
        for (master in masters) {
            val masterDuration = ChronoUnit.MILLIS.between(master.startsAt, master.endsAt)
            val overridesForMaster = overridesByMaster[master.id].orEmpty()
            val supplanted = overridesForMaster.mapNotNullTo(linkedSetOf()) { it.recurrenceId }
            val occurrences = RecurrenceExpander.expand(master, from, to)
            for (start in occurrences) {
                if (start in supplanted) continue
                val end = start.plusNanos(masterDuration * 1_000_000)
                if (end < from) continue
                result += EventOccurrence(
                    event = master,
                    startsAt = start,
                    endsAt = end,
                    recurrenceId = start,
                    isRecurring = true,
                    isException = false
                )
            }
            for (override in overridesForMaster) {
                if (override.startsAt < to && override.endsAt >= from) {
                    result += EventOccurrence(
                        event = override,
                        startsAt = override.startsAt,
                        endsAt = override.endsAt,
                        recurrenceId = override.recurrenceId,
                        isRecurring = true,
                        isException = true
                    )
                }
            }
        }
        return result.sortedBy { it.startsAt }
    }

    override suspend fun getParticipants(authentication: AuthenticationContext?, eventId: UUID): List<EventParticipant> {
        val event = eventRepository.getById(eventId) ?: return emptyList()
        loadVisibleMetadata(authentication, event.metadataId, event.version) ?: return emptyList()
        return participantRepository.getByEventId(eventId)
    }

    override suspend fun addParticipant(
        authentication: AuthenticationContext,
        input: EventParticipantInput
    ): EventParticipant = transaction {
        val event = eventRepository.getById(input.eventId) ?: error("Event not found: ${input.eventId}")
        loadEditableMetadata(authentication, event.metadataId, event.version)
        participantRepository.upsert(input.eventId, input.profileId, input.role, input.status)
    }

    override suspend fun removeParticipant(authentication: AuthenticationContext, eventId: UUID, profileId: UUID) {
        transaction {
            val event = eventRepository.getById(eventId) ?: error("Event not found: $eventId")
            loadEditableMetadata(authentication, event.metadataId, event.version)
            participantRepository.delete(eventId, profileId)
        }
    }

    override suspend fun getAttachments(authentication: AuthenticationContext?, eventId: UUID): List<EventAttachment> {
        val event = eventRepository.getById(eventId) ?: return emptyList()
        loadVisibleMetadata(authentication, event.metadataId, event.version) ?: return emptyList()
        return attachmentRepository.getByEventId(eventId)
    }

    override suspend fun addAttachment(
        authentication: AuthenticationContext,
        input: EventAttachmentInput
    ): EventAttachment = transaction {
        require(input.metadataId != null || input.collectionId != null) { "Either metadataId or collectionId is required" }
        require(input.metadataId == null || input.collectionId == null) { "Only one of metadataId or collectionId may be set" }
        val event = eventRepository.getById(input.eventId) ?: error("Event not found: ${input.eventId}")
        loadEditableMetadata(authentication, event.metadataId, event.version)
        attachmentRepository.add(input.eventId, input.metadataId, input.collectionId, input.relationship)
    }

    override suspend fun removeAttachment(authentication: AuthenticationContext, attachmentId: UUID) {
        transaction {
            val attachment = attachmentRepository.getById(attachmentId) ?: error("Attachment not found: $attachmentId")
            val event = eventRepository.getById(attachment.eventId) ?: error("Event not found: ${attachment.eventId}")
            loadEditableMetadata(authentication, event.metadataId, event.version)
            attachmentRepository.deleteById(attachmentId)
        }
    }

    private fun validateCalendarInput(input: CalendarInput) {
        require(input.color.isNotBlank()) { "Calendar color must not be blank" }
    }

    private fun validateEventInput(input: CalendarEventInput) {
        require(input.title.isNotBlank()) { "Event title must not be blank" }
        require(!input.endsAt.isBefore(input.startsAt)) { "endsAt must not be before startsAt" }
        input.rrule?.takeIf { it.isNotBlank() }?.let {
            // Fail fast on a malformed RRULE rather than at expansion time.
            net.fortuna.ical4j.model.Recur<OffsetDateTime>(it)
        }
    }
}

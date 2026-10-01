package bosca.calendar.service

import bosca.calendar.model.Calendar
import bosca.calendar.model.CalendarEvent
import bosca.calendar.model.CalendarEventInput
import bosca.calendar.model.CalendarInput
import bosca.calendar.model.OccurrenceInput
import bosca.calendar.repository.CalendarEventRepository
import bosca.calendar.repository.CalendarRepository
import bosca.calendar.repository.EventAttachmentRepository
import bosca.calendar.repository.EventParticipantRepository
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarServiceImplTest {

    private val calendarRepository = mockk<CalendarRepository>(relaxed = true)
    private val eventRepository = mockk<CalendarEventRepository>(relaxed = true)
    private val participantRepository = mockk<EventParticipantRepository>(relaxed = true)
    private val attachmentRepository = mockk<EventAttachmentRepository>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val metadataPermissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    private lateinit var service: CalendarServiceImpl

    private val metadataId = UUID.random()
    private val version = 1
    private val calendarRow = Calendar(metadataId = metadataId, version = version, color = "#3b82f6")
    private val parentMetadata = mockk<Metadata>(relaxed = true)

    @BeforeTest
    fun setup() {
        // Make `transaction { block }` execute the block inline so we don't need
        // a real connection in pure-unit tests of the service.
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }

        service = CalendarServiceImpl(
            calendarRepository,
            eventRepository,
            participantRepository,
            attachmentRepository,
            metadataService,
            metadataPermissions
        )

        every { parentMetadata.id } returns metadataId
        every { parentMetadata.version } returns version
        coEvery { metadataService.getById(metadataId, version) } returns parentMetadata
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): OffsetDateTime =
        OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC)

    private fun grantView() {
        coEvery { metadataPermissions.isAllowed(authentication, parentMetadata, PermissionAction.VIEW) } returns true
    }

    private fun denyView() {
        coEvery { metadataPermissions.isAllowed(authentication, parentMetadata, PermissionAction.VIEW) } returns false
    }

    private fun grantEdit() {
        coEvery { metadataPermissions.verifyAllowed(authentication, parentMetadata, PermissionAction.EDIT) } returns Unit
    }

    private fun denyEdit() {
        coEvery {
            metadataPermissions.verifyAllowed(authentication, parentMetadata, PermissionAction.EDIT)
        } throws SecurityException("denied")
    }

    private fun masterEvent(
        id: UUID = UUID.random(),
        rrule: String? = "FREQ=WEEKLY;BYDAY=MO",
        startsAt: OffsetDateTime = at(2026, 1, 5),
        endsAt: OffsetDateTime = at(2026, 1, 5, hour = 10),
        exdates: kotlinx.serialization.json.JsonElement? = null
    ) = CalendarEvent(
        id = id,
        metadataId = metadataId,
        version = version,
        title = "standup",
        startsAt = startsAt,
        endsAt = endsAt,
        rrule = rrule,
        exdates = exdates
    )

    private fun overrideEvent(
        masterId: UUID,
        recurrenceId: OffsetDateTime,
        startsAt: OffsetDateTime = recurrenceId,
        endsAt: OffsetDateTime = recurrenceId.plusHours(1)
    ) = CalendarEvent(
        id = UUID.random(),
        metadataId = metadataId,
        version = version,
        title = "moved",
        startsAt = startsAt,
        endsAt = endsAt,
        originalEventId = masterId,
        recurrenceId = recurrenceId
    )

    // --- getAllCalendars ---

    @Test
    fun `getAllCalendars filters out calendars whose metadata is not viewable`() = runTest {
        val visible = Calendar(metadataId = metadataId, version = version, color = "#000")
        val hiddenMetaId = UUID.random()
        val hidden = Calendar(metadataId = hiddenMetaId, version = 1, color = "#fff")
        coEvery { calendarRepository.getAll() } returns listOf(visible, hidden)
        val hiddenMeta = mockk<Metadata>(relaxed = true)
        coEvery { metadataService.getById(hiddenMetaId, 1) } returns hiddenMeta
        grantView()
        coEvery { metadataPermissions.isAllowed(authentication, hiddenMeta, PermissionAction.VIEW) } returns false

        val out = service.getAllCalendars(authentication)

        assertEquals(listOf(visible), out)
    }

    @Test
    fun `getAllCalendars returns empty when there are no calendars`() = runTest {
        coEvery { calendarRepository.getAll() } returns emptyList()
        val out = service.getAllCalendars(authentication)
        assertTrue(out.isEmpty())
    }

    // --- getCalendar ---

    @Test
    fun `getCalendar returns null when metadata is missing`() = runTest {
        coEvery { metadataService.getById(metadataId, version) } returns null
        assertNull(service.getCalendar(authentication, metadataId, version))
    }

    @Test
    fun `getCalendar returns null when caller cannot VIEW`() = runTest {
        denyView()
        assertNull(service.getCalendar(authentication, metadataId, version))
    }

    @Test
    fun `getCalendar returns the row when caller can VIEW`() = runTest {
        grantView()
        coEvery { calendarRepository.getById(metadataId, version) } returns calendarRow
        assertEquals(calendarRow, service.getCalendar(authentication, metadataId, version))
    }

    // --- createCalendar ---

    @Test
    fun `createCalendar requires EDIT and inserts via repository`() = runTest {
        grantEdit()
        coEvery {
            calendarRepository.add(metadataId, version, "#fff", "")
        } returns calendarRow
        val out = service.createCalendar(authentication, metadataId, version, CalendarInput(color = "#fff"))
        assertEquals(calendarRow, out)
        coVerify { metadataPermissions.verifyAllowed(authentication, parentMetadata, PermissionAction.EDIT) }
    }

    @Test
    fun `createCalendar is idempotent -- falls back to existing row on conflict`() = runTest {
        grantEdit()
        coEvery { calendarRepository.add(any(), any(), any(), any()) } returns null
        coEvery { calendarRepository.getById(metadataId, version) } returns calendarRow
        val out = service.createCalendar(authentication, metadataId, version, CalendarInput(color = "#fff"))
        assertEquals(calendarRow, out)
    }

    @Test
    fun `createCalendar throws when EDIT is denied`() = runTest {
        denyEdit()
        assertFailsWith<SecurityException> {
            service.createCalendar(authentication, metadataId, version, CalendarInput(color = "#fff"))
        }
    }

    @Test
    fun `createCalendar rejects blank color`() = runTest {
        grantEdit()
        assertFailsWith<IllegalArgumentException> {
            service.createCalendar(authentication, metadataId, version, CalendarInput(color = ""))
        }
    }

    // --- editCalendar ---

    @Test
    fun `editCalendar requires EDIT and writes through`() = runTest {
        grantEdit()
        coEvery { calendarRepository.update(metadataId, version, "#abc", "desc") } returns calendarRow
        val out = service.editCalendar(authentication, metadataId, version, CalendarInput(color = "#abc", description = "desc"))
        assertEquals(calendarRow, out)
    }

    // --- getOccurrences ---

    @Test
    fun `getOccurrences rejects inverted range`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.getOccurrences(authentication, metadataId, version, at(2026, 6, 1), at(2026, 1, 1))
        }
    }

    @Test
    fun `getOccurrences returns empty when caller cannot VIEW`() = runTest {
        denyView()
        val out = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 1, 31))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `getOccurrences expands a master and applies overrides`() = runTest {
        grantView()
        val master = masterEvent(rrule = "FREQ=WEEKLY;BYDAY=MO", startsAt = at(2026, 1, 5))
        val ovr = overrideEvent(
            masterId = master.id,
            recurrenceId = at(2026, 1, 12),
            startsAt = at(2026, 1, 12, 14),
            endsAt = at(2026, 1, 12, 15)
        )
        coEvery { eventRepository.getCandidatesForRange(metadataId, version, any(), any()) } returns listOf(master)
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns listOf(ovr)

        val out = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 2, 1))

        // Mondays Jan 5, 12, 19, 26 — but Jan 12 is supplanted by the override (which still appears)
        val starts = out.map { it.startsAt }
        assertTrue(at(2026, 1, 5) in starts)
        assertTrue(at(2026, 1, 19) in starts)
        assertTrue(at(2026, 1, 26) in starts)
        // Override appears at its own moved time
        assertTrue(at(2026, 1, 12, 14) in starts)
        // Original Jan 12 9am is suppressed
        assertTrue(at(2026, 1, 12, 9) !in starts)
        // The override should be marked as exception
        val moved = out.first { it.startsAt == at(2026, 1, 12, 14) }
        assertTrue(moved.isException)
        assertTrue(moved.isRecurring)
    }

    @Test
    fun `getOccurrences passes through singles unchanged`() = runTest {
        grantView()
        val single = masterEvent(rrule = null, startsAt = at(2026, 1, 5))
        coEvery { eventRepository.getCandidatesForRange(metadataId, version, any(), any()) } returns listOf(single)

        val out = service.getOccurrences(authentication, metadataId, version, at(2026, 1, 1), at(2026, 1, 31))

        assertEquals(1, out.size)
        assertEquals(single.startsAt, out[0].startsAt)
        assertTrue(!out[0].isRecurring)
        assertTrue(!out[0].isException)
    }

    // --- getOccurrencesForCalendars ---

    @Test
    fun `getOccurrencesForCalendars returns empty when nothing visible`() = runTest {
        denyView()
        val out = service.getOccurrencesForCalendars(
            authentication, listOf(metadataId to version), at(2026, 1, 1), at(2026, 2, 1)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `getOccurrencesForCalendars returns empty for empty calendar list`() = runTest {
        val out = service.getOccurrencesForCalendars(authentication, emptyList(), at(2026, 1, 1), at(2026, 2, 1))
        assertTrue(out.isEmpty())
    }

    // --- addEvent ---

    @Test
    fun `addEvent requires EDIT and parent calendar must exist`() = runTest {
        grantEdit()
        coEvery { calendarRepository.getById(metadataId, version) } returns null
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalStateException> { service.addEvent(authentication, input) }
    }

    @Test
    fun `addEvent rejects blank title`() = runTest {
        grantEdit()
        coEvery { calendarRepository.getById(metadataId, version) } returns calendarRow
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "  ", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalArgumentException> { service.addEvent(authentication, input) }
    }

    @Test
    fun `addEvent rejects ends before starts`() = runTest {
        grantEdit()
        coEvery { calendarRepository.getById(metadataId, version) } returns calendarRow
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5, 10), endsAt = at(2026, 1, 5, 9)
        )
        assertFailsWith<IllegalArgumentException> { service.addEvent(authentication, input) }
    }

    @Test
    fun `addEvent rejects malformed rrule`() = runTest {
        grantEdit()
        coEvery { calendarRepository.getById(metadataId, version) } returns calendarRow
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10),
            rrule = "totally-bogus"
        )
        assertFailsWith<Exception> { service.addEvent(authentication, input) }
    }

    @Test
    fun `addEvent persists with normalised blank rrule treated as null`() = runTest {
        grantEdit()
        coEvery { calendarRepository.getById(metadataId, version) } returns calendarRow
        val captured = masterEvent(rrule = null)
        coEvery {
            eventRepository.add(metadataId, version, "t", "", "", false, any(), any(), null)
        } returns captured
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10),
            rrule = ""
        )
        val out = service.addEvent(authentication, input)
        assertEquals(captured, out)
    }

    // --- editEvent ---

    @Test
    fun `editEvent rejects moving between calendars`() = runTest {
        coEvery { eventRepository.getById(any()) } returns masterEvent()
        val other = UUID.random()
        val input = CalendarEventInput(
            metadataId = other, version = 1,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalArgumentException> { service.editEvent(authentication, UUID.random(), input) }
    }

    @Test
    fun `editEvent rejects edits to overrides`() = runTest {
        val master = masterEvent()
        val ovr = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 12))
        coEvery { eventRepository.getById(ovr.id) } returns ovr
        grantEdit()
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 12), endsAt = at(2026, 1, 12, 10)
        )
        assertFailsWith<IllegalArgumentException> { service.editEvent(authentication, ovr.id, input) }
    }

    @Test
    fun `editEvent on master with same rrule and start does not prune overrides`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        coEvery {
            eventRepository.update(master.id, any(), any(), any(), any(), master.startsAt, any(), master.rrule)
        } returns master
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "renamed", startsAt = master.startsAt, endsAt = master.endsAt, rrule = master.rrule
        )

        service.editEvent(authentication, master.id, input)

        coVerify(exactly = 0) { eventRepository.getOverridesByMasterIds(any()) }
    }

    @Test
    fun `editEvent demoting a master to single deletes every override and clears EXDATEs`() = runTest {
        val masterStart = at(2026, 1, 5)
        val master = masterEvent(startsAt = masterStart, exdates = JsonArray(listOf(JsonPrimitive(at(2026, 1, 12).toString()))))
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val newMaster = master.copy(rrule = null)
        coEvery {
            eventRepository.update(master.id, any(), any(), any(), any(), any(), any(), null)
        } returns newMaster
        val ovr = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 19))
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns listOf(ovr)

        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "now-single", startsAt = masterStart, endsAt = master.endsAt, rrule = null
        )

        service.editEvent(authentication, master.id, input)

        coVerifyOrder {
            eventRepository.update(master.id, any(), any(), any(), any(), any(), any(), null)
            eventRepository.getOverridesByMasterIds(listOf(master.id))
            eventRepository.deleteById(ovr.id)
            eventRepository.setExdates(master.id, null)
        }
    }

    // --- deleteEvent ---

    @Test
    fun `deleteEvent silently no-ops when event missing`() = runTest {
        coEvery { eventRepository.getById(any()) } returns null
        service.deleteEvent(authentication, UUID.random())
        coVerify(exactly = 0) { eventRepository.deleteById(any()) }
    }

    @Test
    fun `deleteEvent requires EDIT`() = runTest {
        coEvery { eventRepository.getById(any()) } returns masterEvent()
        denyEdit()
        assertFailsWith<SecurityException> { service.deleteEvent(authentication, UUID.random()) }
    }

    // --- editOccurrence ---

    @Test
    fun `editOccurrence rejects non-recurring master`() = runTest {
        val single = masterEvent(rrule = null)
        coEvery { eventRepository.getById(single.id) } returns single
        val input = OccurrenceInput(
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalArgumentException> {
            service.editOccurrence(authentication, single.id, at(2026, 1, 5), input)
        }
    }

    @Test
    fun `editOccurrence rejects recurrenceId that is not a real occurrence`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val input = OccurrenceInput(
            title = "t", startsAt = at(2026, 1, 6), endsAt = at(2026, 1, 6, 10)
        )
        assertFailsWith<IllegalArgumentException> {
            // Tuesday isn't a Monday occurrence
            service.editOccurrence(authentication, master.id, at(2026, 1, 6), input)
        }
    }

    @Test
    fun `editOccurrence creates an override at a real occurrence`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val targetStart = at(2026, 1, 12, 14)
        val targetEnd = at(2026, 1, 12, 15)
        val ovr = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 12), startsAt = targetStart, endsAt = targetEnd)
        coEvery {
            eventRepository.upsertOverride(
                metadataId, version, "moved", "", "", false,
                targetStart, targetEnd, master.id, at(2026, 1, 12)
            )
        } returns ovr

        val input = OccurrenceInput(
            title = "moved", startsAt = targetStart, endsAt = targetEnd
        )
        val out = service.editOccurrence(authentication, master.id, at(2026, 1, 12), input)

        assertEquals(ovr, out)
    }

    // --- cancelOccurrence ---

    @Test
    fun `cancelOccurrence appends EXDATE and deletes any matching override`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val ovr = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 12))
        coEvery { eventRepository.getOverride(master.id, at(2026, 1, 12)) } returns ovr

        service.cancelOccurrence(authentication, master.id, at(2026, 1, 12))

        coVerify { eventRepository.deleteById(ovr.id) }
        coVerify { eventRepository.setExdates(master.id, any()) }
    }

    @Test
    fun `cancelOccurrence rejects when target is not an occurrence`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        assertFailsWith<IllegalArgumentException> {
            service.cancelOccurrence(authentication, master.id, at(2026, 1, 6))
        }
    }

    // --- splitSeriesAt / endSeriesAt ---

    @Test
    fun `splitSeriesAt caps original master and creates successor`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        coEvery { eventRepository.updateRrule(master.id, any()) } returns master
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns emptyList()
        val newMaster = master.copy(id = UUID.random(), title = "renamed")
        coEvery { eventRepository.add(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns newMaster

        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "renamed", startsAt = at(2026, 1, 19), endsAt = at(2026, 1, 19, 10),
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )
        val out = service.splitSeriesAt(authentication, master.id, at(2026, 1, 19), input)

        assertEquals(newMaster, out)
        coVerify { eventRepository.updateRrule(master.id, match { it.contains("UNTIL=") }) }
    }

    @Test
    fun `splitSeriesAt rejects cross-calendar input`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        val input = CalendarEventInput(
            metadataId = UUID.random(), version = 1,
            title = "t", startsAt = at(2026, 1, 19), endsAt = at(2026, 1, 19, 10)
        )
        assertFailsWith<IllegalArgumentException> {
            service.splitSeriesAt(authentication, master.id, at(2026, 1, 19), input)
        }
    }

    @Test
    fun `endSeriesAt caps original master and prunes overrides at or after the cut`() = runTest {
        val master = masterEvent()
        val ovrBefore = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 12))
        val ovrAfter = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 19))
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        coEvery { eventRepository.updateRrule(master.id, any()) } returns master
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns listOf(ovrBefore, ovrAfter)

        service.endSeriesAt(authentication, master.id, at(2026, 1, 19))

        coVerify(exactly = 1) { eventRepository.deleteById(ovrAfter.id) }
        coVerify(exactly = 0) { eventRepository.deleteById(ovrBefore.id) }
    }

    @Test
    fun `endSeriesAt rejects non-recurring`() = runTest {
        val single = masterEvent(rrule = null)
        coEvery { eventRepository.getById(single.id) } returns single
        assertFailsWith<IllegalStateException> {
            service.endSeriesAt(authentication, single.id, at(2026, 1, 5))
        }
    }

    // --- additional branch coverage ---

    @Test
    fun `editEvent with rrule changed but startsAt unchanged prunes orphan overrides`() = runTest {
        val masterStart = at(2026, 1, 5) // Monday
        val master = masterEvent(rrule = "FREQ=WEEKLY;BYDAY=MO", startsAt = masterStart)
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val updated = master.copy(rrule = "FREQ=WEEKLY;BYDAY=TU")
        coEvery {
            eventRepository.update(master.id, any(), any(), any(), any(), masterStart, any(), "FREQ=WEEKLY;BYDAY=TU")
        } returns updated
        val ovrMonday = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 12))
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns listOf(ovrMonday)

        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = master.title, startsAt = masterStart, endsAt = master.endsAt,
            rrule = "FREQ=WEEKLY;BYDAY=TU"
        )

        service.editEvent(authentication, master.id, input)

        // Monday override no longer matches a Tuesday-only rule → deleted
        coVerify { eventRepository.deleteById(ovrMonday.id) }
    }

    @Test
    fun `editEvent with startsAt changed but rrule unchanged prunes orphan overrides`() = runTest {
        val master = masterEvent(rrule = "FREQ=DAILY", startsAt = at(2026, 1, 5))
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val newStart = at(2026, 6, 1)
        val updated = master.copy(startsAt = newStart, endsAt = newStart.plusHours(1))
        coEvery {
            eventRepository.update(master.id, any(), any(), any(), any(), newStart, any(), master.rrule)
        } returns updated
        // An override at a recurrence_id before the new seed start → orphan
        val ovr = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 6))
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns listOf(ovr)

        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = master.title, startsAt = newStart, endsAt = newStart.plusHours(1),
            rrule = master.rrule
        )

        service.editEvent(authentication, master.id, input)

        coVerify { eventRepository.deleteById(ovr.id) }
    }

    @Test
    fun `editEvent keeps overrides whose recurrenceId still matches the new master`() = runTest {
        val master = masterEvent(rrule = "FREQ=WEEKLY;BYDAY=MO", startsAt = at(2026, 1, 5))
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        // Same rrule, same startsAt — no pruning path triggered. But change the endsAt.
        val updated = master.copy(endsAt = master.endsAt.plusMinutes(30))
        coEvery {
            eventRepository.update(master.id, any(), any(), any(), any(), master.startsAt, any(), master.rrule)
        } returns updated

        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = master.title, startsAt = master.startsAt, endsAt = master.endsAt.plusMinutes(30),
            rrule = master.rrule
        )

        service.editEvent(authentication, master.id, input)

        // Pruning path should be skipped because rrule and startsAt are unchanged.
        coVerify(exactly = 0) { eventRepository.getOverridesByMasterIds(any()) }
        coVerify(exactly = 0) { eventRepository.deleteById(any()) }
    }

    @Test
    fun `editEvent retains EXDATEs that still match a generated occurrence`() = runTest {
        val master = masterEvent(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startsAt = at(2026, 1, 5),
            exdates = JsonArray(
                listOf(
                    JsonPrimitive(at(2026, 1, 12).toString()), // Monday — keep on the new rule
                    JsonPrimitive(at(2026, 1, 13).toString())  // Tuesday — drop on Monday-only rule
                )
            )
        )
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val updated = master.copy(rrule = "FREQ=WEEKLY;BYDAY=MO")
        coEvery {
            eventRepository.update(master.id, any(), any(), any(), any(), master.startsAt, any(), "FREQ=WEEKLY;BYDAY=MO")
        } returns updated
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns emptyList()

        // Force the prune path even though rrule/startsAt logically unchanged: change rrule by case
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = master.title, startsAt = master.startsAt, endsAt = master.endsAt,
            // String-different from master.rrule so the equality check fires
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )
        // master.rrule already equals input.rrule, so we need to mock a different existing rrule
        val masterDifferent = master.copy(rrule = "FREQ=DAILY")
        coEvery { eventRepository.getById(master.id) } returns masterDifferent
        coEvery {
            eventRepository.update(master.id, any(), any(), any(), any(), master.startsAt, any(), "FREQ=WEEKLY;BYDAY=MO")
        } returns updated

        service.editEvent(authentication, master.id, input)

        // Expect setExdates called with only the surviving Monday entry (Tuesday pruned)
        coVerify {
            eventRepository.setExdates(master.id, match {
                val arr = it as JsonArray
                arr.size == 1 &&
                    (arr[0] as JsonPrimitive).content == at(2026, 1, 12).toString()
            })
        }
    }

    @Test
    fun `splitSeriesAt rejects bogus recurrenceId`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t",
            startsAt = at(2026, 1, 6), endsAt = at(2026, 1, 6, 10), // Tuesday — not a Monday occurrence
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )
        assertFailsWith<IllegalArgumentException> {
            service.splitSeriesAt(authentication, master.id, at(2026, 1, 6), input)
        }
    }

    @Test
    fun `splitSeriesAt with omitted rrule reuses the master's rrule`() = runTest {
        val master = masterEvent(rrule = "FREQ=WEEKLY;BYDAY=MO")
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        coEvery { eventRepository.updateRrule(master.id, any()) } returns master
        coEvery { eventRepository.getOverridesByMasterIds(listOf(master.id)) } returns emptyList()
        val newMaster = master.copy(id = UUID.random())
        coEvery {
            eventRepository.add(any(), any(), any(), any(), any(), any(), any(), any(), master.rrule)
        } returns newMaster

        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = master.title,
            startsAt = at(2026, 1, 19), endsAt = at(2026, 1, 19, 10),
            rrule = null // omitted → service should fall back to master.rrule
        )

        service.splitSeriesAt(authentication, master.id, at(2026, 1, 19), input)

        coVerify {
            // The successor add() was called with the inherited rrule.
            eventRepository.add(any(), any(), any(), any(), any(), any(), any(), any(), master.rrule)
        }
    }

    @Test
    fun `splitSeriesAt with overrides on both sides keeps before-cut and drops after-cut`() = runTest {
        val master = masterEvent(rrule = "FREQ=WEEKLY;BYDAY=MO")
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        coEvery { eventRepository.updateRrule(master.id, any()) } returns master
        val ovrBefore = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 12))
        val ovrAtCut = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 19))
        val ovrAfter = overrideEvent(masterId = master.id, recurrenceId = at(2026, 1, 26))
        coEvery {
            eventRepository.getOverridesByMasterIds(listOf(master.id))
        } returns listOf(ovrBefore, ovrAtCut, ovrAfter)
        val newMaster = master.copy(id = UUID.random())
        coEvery {
            eventRepository.add(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns newMaster

        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "renamed",
            startsAt = at(2026, 1, 19), endsAt = at(2026, 1, 19, 10),
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )

        service.splitSeriesAt(authentication, master.id, at(2026, 1, 19), input)

        coVerify(exactly = 0) { eventRepository.deleteById(ovrBefore.id) }
        coVerify(exactly = 1) { eventRepository.deleteById(ovrAtCut.id) }
        coVerify(exactly = 1) { eventRepository.deleteById(ovrAfter.id) }
    }

    @Test
    fun `endSeriesAt rejects bogus recurrenceId`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        assertFailsWith<IllegalArgumentException> {
            service.endSeriesAt(authentication, master.id, at(2026, 1, 6)) // Tuesday
        }
    }

    @Test
    fun `editOccurrence rejects when master is missing`() = runTest {
        coEvery { eventRepository.getById(any()) } returns null
        val input = OccurrenceInput(
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalStateException> {
            service.editOccurrence(authentication, UUID.random(), at(2026, 1, 5), input)
        }
    }

    @Test
    fun `editOccurrence rejects when caller lacks EDIT`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        denyEdit()
        val input = OccurrenceInput(
            title = "t", startsAt = at(2026, 1, 12), endsAt = at(2026, 1, 12, 10)
        )
        assertFailsWith<SecurityException> {
            service.editOccurrence(authentication, master.id, at(2026, 1, 12), input)
        }
    }

    @Test
    fun `editOccurrence rejects blank title`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        val input = OccurrenceInput(
            title = "  ", startsAt = at(2026, 1, 12), endsAt = at(2026, 1, 12, 10)
        )
        assertFailsWith<IllegalArgumentException> {
            service.editOccurrence(authentication, master.id, at(2026, 1, 12), input)
        }
    }

    @Test
    fun `editOccurrence rejects ends before starts`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        val input = OccurrenceInput(
            title = "t", startsAt = at(2026, 1, 12, 10), endsAt = at(2026, 1, 12, 9)
        )
        assertFailsWith<IllegalArgumentException> {
            service.editOccurrence(authentication, master.id, at(2026, 1, 12), input)
        }
    }

    @Test
    fun `cancelOccurrence rejects non-recurring master`() = runTest {
        val single = masterEvent(rrule = null)
        coEvery { eventRepository.getById(single.id) } returns single
        assertFailsWith<IllegalArgumentException> {
            service.cancelOccurrence(authentication, single.id, at(2026, 1, 5))
        }
    }

    @Test
    fun `cancelOccurrence with no existing override only writes EXDATE`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        grantEdit()
        coEvery { eventRepository.getOverride(master.id, at(2026, 1, 12)) } returns null

        service.cancelOccurrence(authentication, master.id, at(2026, 1, 12))

        coVerify(exactly = 0) { eventRepository.deleteById(any()) }
        coVerify { eventRepository.setExdates(master.id, any()) }
    }

    @Test
    fun `getOccurrencesForCalendars returns events only for visible calendars`() = runTest {
        val viewableId = metadataId
        val hiddenId = UUID.random()
        val viewableMeta = parentMetadata
        val hiddenMeta = mockk<Metadata>(relaxed = true)
        coEvery { metadataService.getById(hiddenId, version) } returns hiddenMeta
        coEvery { metadataPermissions.isAllowed(authentication, viewableMeta, PermissionAction.VIEW) } returns true
        coEvery { metadataPermissions.isAllowed(authentication, hiddenMeta, PermissionAction.VIEW) } returns false
        val viewableEvent = masterEvent(rrule = null, startsAt = at(2026, 1, 5)).copy(metadataId = viewableId)
        coEvery {
            eventRepository.getCandidatesForRange(viewableId, version, any(), any())
        } returns listOf(viewableEvent)

        val out = service.getOccurrencesForCalendars(
            authentication,
            calendars = listOf(viewableId to version, hiddenId to version),
            from = at(2026, 1, 1),
            to = at(2026, 2, 1)
        )

        // Only the viewable calendar's event surfaces; hidden one is skipped at the VIEW gate
        // and therefore its repository is never queried.
        assertEquals(1, out.size)
        assertEquals(viewableEvent.id, out[0].event.id)
        coVerify(exactly = 0) { eventRepository.getCandidatesForRange(hiddenId, any(), any(), any()) }
    }

    @Test
    fun `getOccurrencesForCalendars rejects inverted range`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.getOccurrencesForCalendars(
                authentication, listOf(metadataId to version),
                at(2026, 6, 1), at(2026, 1, 1)
            )
        }
    }

    @Test
    fun `editEvent throws when caller lacks EDIT after locating the row`() = runTest {
        coEvery { eventRepository.getById(any()) } returns masterEvent()
        denyEdit()
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<SecurityException> { service.editEvent(authentication, UUID.random(), input) }
    }

    @Test
    fun `splitSeriesAt rejects non-recurring`() = runTest {
        val single = masterEvent(rrule = null)
        coEvery { eventRepository.getById(single.id) } returns single
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalStateException> {
            service.splitSeriesAt(authentication, single.id, at(2026, 1, 5), input)
        }
    }

    @Test
    fun `cancelOccurrence rejects when caller lacks EDIT`() = runTest {
        val master = masterEvent()
        coEvery { eventRepository.getById(master.id) } returns master
        denyEdit()
        assertFailsWith<SecurityException> {
            service.cancelOccurrence(authentication, master.id, at(2026, 1, 12))
        }
    }

    @Test
    fun `cancelOccurrence rejects when master is missing`() = runTest {
        coEvery { eventRepository.getById(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.cancelOccurrence(authentication, UUID.random(), at(2026, 1, 12))
        }
    }

    @Test
    fun `splitSeriesAt rejects when master is missing`() = runTest {
        coEvery { eventRepository.getById(any()) } returns null
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalStateException> {
            service.splitSeriesAt(authentication, UUID.random(), at(2026, 1, 5), input)
        }
    }

    @Test
    fun `endSeriesAt rejects when master is missing`() = runTest {
        coEvery { eventRepository.getById(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.endSeriesAt(authentication, UUID.random(), at(2026, 1, 5))
        }
    }

    @Test
    fun `editEvent rejects when row is missing`() = runTest {
        coEvery { eventRepository.getById(any()) } returns null
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<IllegalStateException> {
            service.editEvent(authentication, UUID.random(), input)
        }
    }

    @Test
    fun `getEvent returns null when row is missing`() = runTest {
        coEvery { eventRepository.getById(any()) } returns null
        assertNull(service.getEvent(authentication, UUID.random()))
    }

    @Test
    fun `getEvent returns null when caller lacks VIEW`() = runTest {
        val ev = masterEvent(rrule = null)
        coEvery { eventRepository.getById(ev.id) } returns ev
        denyView()
        assertNull(service.getEvent(authentication, ev.id))
    }

    @Test
    fun `getEvent returns the event when caller can VIEW`() = runTest {
        val ev = masterEvent(rrule = null)
        coEvery { eventRepository.getById(ev.id) } returns ev
        grantView()
        assertEquals(ev, service.getEvent(authentication, ev.id))
    }

    @Test
    fun `editCalendar rejects when caller lacks EDIT`() = runTest {
        denyEdit()
        assertFailsWith<SecurityException> {
            service.editCalendar(authentication, metadataId, version, CalendarInput(color = "#abc"))
        }
    }

    @Test
    fun `addEvent rejects when caller lacks EDIT`() = runTest {
        denyEdit()
        val input = CalendarEventInput(
            metadataId = metadataId, version = version,
            title = "t", startsAt = at(2026, 1, 5), endsAt = at(2026, 1, 5, 10)
        )
        assertFailsWith<SecurityException> { service.addEvent(authentication, input) }
    }
}

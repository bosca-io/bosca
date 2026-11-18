package bosca.design.catalog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogCalendarTest {
    @Test
    fun `create validates draft retains errors and appends an editable occurrence`() {
        val state = CatalogCalendar("test")
        val before = state.events
        state.slot("2026-12-31", 23)
        assertEquals("2026-12-31T23:00", state.draft.startsAt)
        assertEquals("2027-01-01T00:00", state.draft.endsAt)
        state.create("Draft", state.draft.startsAt, "2026-12-31T22:00")
        assertEquals(before, state.events)
        assertFalse(state.createCompleted)
        assertTrue(state.createError.isNotEmpty())
        assertEquals("Draft", state.draft.title)
        state.create("  New meeting  ", state.draft.startsAt, "2027-01-01T00:00")
        assertEquals(before.size + 1, state.events.size)
        assertTrue(state.createCompleted)
        assertEquals("New meeting", state.events.last().title)
        assertTrue(state.events.last().editable)
        assertEquals("2026-12-31", state.current)
        val restored = Json.decodeFromString(CatalogCalendar.serializer(), Json.encodeToString(CatalogCalendar.serializer(), state))
        assertEquals(state.events, restored.events)
        state.newEvent()
        assertFalse(state.createCompleted)
        assertEquals(2, state.createRequest)
        assertEquals("", state.draft.title)
    }

    @Test
    fun `blank titles and malformed times preserve the event list`() {
        val state = CatalogCalendar("test")
        val before = state.events
        state.create(" ", "2026-11-05T09:00", "2026-11-05T10:00")
        assertEquals("Enter an event title.", state.createError)
        state.create("Title", "bad", "2026-11-05T10:00")
        assertEquals("Enter valid start and end times.", state.createError)
        assertEquals(before, state.events)
    }
    @Test
    fun `editing and deletion retain details and participants`() {
        val state=CatalogCalendar("test")
        state.selected("planning")
        state.create("Edited", "2026-11-05T09:00", "2026-11-05T10:30", "personal", "Description", "Room 2", "timed", "[\"alex\",\"jamie\"]")
        val saved=state.events.first { it.id=="planning" }
        assertEquals("Room 2",saved.location); assertEquals("Description",saved.description)
        assertEquals(listOf("alex","jamie"),saved.participants.map { it.profileId })
        assertEquals("personal",saved.calendarId)
        state.selected("planning"); state.remove("all")
        assertFalse(state.events.any { it.id=="planning" }); assertTrue(state.deleteCompleted)
    }

    private fun series(): CatalogCalendar = CatalogCalendar("test").apply {
        events=emptyList()
        create("Series", "2026-11-05T09:00", "2026-11-05T10:00", rrule="FREQ=DAILY;COUNT=4")
    }

    @Test
    fun `this occurrence edits and deletes preserve the rest of a series`() {
        val state=series(); val original=state.occurrences()
        assertEquals(4,original.size)
        state.selected(original[1].id)
        state.create("Exception",original[1].startsAt,original[1].endsAt,rrule=state.draft.rrule,scope="this")
        assertEquals(listOf("Series","Exception","Series","Series"),state.occurrences().map { it.title })
        state.selected(original[0].id); state.remove("this")
        assertEquals(3,state.occurrences().size)
        state.selected(original[2].id); state.remove("following")
        assertEquals(listOf("Exception"),state.occurrences().map { it.title })
    }

    @Test
    fun `following edits split a finite series without extending its count`() {
        val state=series(); val before=state.occurrences()
        state.selected(before[2].id)
        state.create("Following","2026-11-07T11:00","2026-11-07T12:00",rrule=state.draft.rrule,scope="following")
        assertEquals(2,state.events.size)
        val occurrences=state.occurrences().sortedBy { it.startsAt }
        assertEquals(4,occurrences.size)
        assertEquals(listOf("Series","Series","Following","Following"),occurrences.map { it.title })
    }

    @Test
    fun `entire series edits shift the seed and deletion removes every occurrence`() {
        val state=series(); state.selected(state.occurrences()[1].id)
        state.create("All", "2026-11-06T11:00", "2026-11-06T12:00",rrule=state.draft.rrule,scope="all")
        assertEquals(4,state.occurrences().size)
        assertTrue(state.occurrences().all { it.title=="All" && java.time.Instant.parse(it.startsAt).atOffset(java.time.ZoneOffset.UTC).hour==11 })
        state.selected(state.occurrences()[1].id); state.remove("all")
        assertTrue(state.events.isEmpty()); assertTrue(state.occurrences().isEmpty())
    }

    @Test
    fun `daily recurrence retains local wall time across daylight saving changes`() {
        val state=CatalogCalendar("dst"); state.events=emptyList(); state.current="2026-11-01"
        state.create("DST", "2026-10-31T13:00:00Z", "2026-10-31T14:00:00Z",rrule="FREQ=DAILY;COUNT=3",timeZone="America/New_York")
        state.current="2026-11-01"
        assertEquals(listOf(13,14,14),state.occurrences().map { java.time.Instant.parse(it.startsAt).atOffset(java.time.ZoneOffset.UTC).hour })
    }

    @Test
    fun `calendar management filters events and rejected moves preserve data`() {
        val state=CatalogCalendar("test"); val before=state.events
        state.toggleCalendar("personal"); assertFalse(state.occurrences().any { it.calendarId=="personal" })
        state.rejectMoves=true
        kotlin.test.assertFailsWith<IllegalStateException> { state.move("planning","2026-11-05T10:00:00Z","2026-11-05T11:00:00Z") }
        assertEquals(before,state.events)
        state.newCalendar(); state.saveCalendar("New", "#ff0022", "Notes")
        val created=state.calendars.last(); assertTrue(created.id in state.visibleIds)
        state.editCalendar(created.id); state.saveCalendar("Renamed", "#0022ff", "Updated")
        assertEquals("Renamed",state.calendars.last().name)
        state.removeCalendar(); assertFalse(state.calendars.any { it.id==created.id })
    }
    @Test(timeout = 5000)
    fun `until dates and weekly weekdays expand in the application range`() {
        val state=CatalogCalendar("until"); state.events=emptyList()
        state.create("Until", "2026-11-05T09:00", "2026-11-05T10:00", rrule="FREQ=DAILY;UNTIL=20261107T235959Z")
        assertEquals(3,state.occurrences().size)
        state.events=emptyList(); state.editingId=""
        state.create("Weekdays", "2026-11-05T09:00", "2026-11-05T10:00", rrule="FREQ=WEEKLY;BYDAY=MO,TH;COUNT=4")
        assertEquals(4,state.occurrences().size)
    }

}

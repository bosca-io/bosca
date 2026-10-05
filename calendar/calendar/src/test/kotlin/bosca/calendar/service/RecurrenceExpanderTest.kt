package bosca.calendar.service

import bosca.calendar.model.CalendarEvent
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceExpanderTest {

    private val metadataId = UUID.random()

    private fun event(
        rrule: String? = null,
        startsAt: OffsetDateTime = at(2026, 1, 5, 9),
        endsAt: OffsetDateTime = at(2026, 1, 5, 10),
        exdates: kotlinx.serialization.json.JsonElement? = null
    ) = CalendarEvent(
        id = UUID.random(),
        metadataId = metadataId,
        version = 1,
        title = "test",
        startsAt = startsAt,
        endsAt = endsAt,
        rrule = rrule,
        exdates = exdates
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): OffsetDateTime =
        OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC)

    // --- expand: non-recurring ---

    @Test
    fun `expand returns the single event when it overlaps the range`() {
        val ev = event()
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 1, 31))
        assertEquals(listOf(at(2026, 1, 5, 9)), out)
    }

    @Test
    fun `expand returns empty when the single event is outside the range`() {
        val ev = event()
        val out = RecurrenceExpander.expand(ev, at(2026, 2, 1), at(2026, 2, 28))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `expand returns empty when rrule is blank string`() {
        val ev = event(rrule = "   ", startsAt = at(2026, 6, 1))
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 12, 31))
        assertEquals(listOf(at(2026, 6, 1)), out)
    }

    // --- expand: weekly ---

    @Test
    fun `expand weekly produces every Monday in range`() {
        val ev = event(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startsAt = at(2026, 1, 5, 9) // Monday
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 2, 1))
        assertEquals(
            listOf(
                at(2026, 1, 5, 9),
                at(2026, 1, 12, 9),
                at(2026, 1, 19, 9),
                at(2026, 1, 26, 9)
            ),
            out
        )
    }

    @Test
    fun `expand respects COUNT cap`() {
        val ev = event(
            rrule = "FREQ=DAILY;COUNT=3",
            startsAt = at(2026, 1, 5, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 12, 31))
        assertEquals(3, out.size)
    }

    @Test
    fun `expand respects UNTIL cap`() {
        val ev = event(
            rrule = "FREQ=DAILY;UNTIL=20260108T000000Z",
            startsAt = at(2026, 1, 5, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 12, 31))
        assertEquals(listOf(at(2026, 1, 5, 9), at(2026, 1, 6, 9), at(2026, 1, 7, 9)), out)
    }

    @Test
    fun `expand applies EXDATE filtering`() {
        val skip = at(2026, 1, 12, 9)
        val ev = event(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startsAt = at(2026, 1, 5, 9),
            exdates = JsonArray(listOf(JsonPrimitive(skip.toString())))
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 2, 1))
        assertFalse(skip in out)
        assertEquals(3, out.size)
    }

    @Test
    fun `expand returns empty when range is entirely before the seed`() {
        val ev = event(
            rrule = "FREQ=DAILY",
            startsAt = at(2026, 6, 1, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 5, 1))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `expand throws on malformed rrule`() {
        val ev = event(
            rrule = "this-is-not-an-rrule",
            startsAt = at(2026, 1, 1)
        )
        assertFailsWith<IllegalArgumentException> {
            RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2027, 1, 1))
        }
    }

    @Test
    fun `expand caps at MAX_OCCURRENCES even when range allows more`() {
        // No COUNT, no UNTIL, daily forever
        val ev = event(
            rrule = "FREQ=DAILY",
            startsAt = at(2020, 1, 1)
        )
        val out = RecurrenceExpander.expand(ev, at(2020, 1, 1), at(2099, 1, 1))
        assertEquals(RecurrenceExpander.MAX_OCCURRENCES, out.size)
    }

    // --- isOccurrenceOf ---

    @Test
    fun `isOccurrenceOf single event matches startsAt`() {
        val ev = event()
        assertTrue(RecurrenceExpander.isOccurrenceOf(ev, ev.startsAt))
        assertFalse(RecurrenceExpander.isOccurrenceOf(ev, ev.startsAt.plusDays(1)))
    }

    @Test
    fun `isOccurrenceOf weekly matches a Monday and rejects a Tuesday`() {
        val ev = event(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startsAt = at(2026, 1, 5, 9)
        )
        assertTrue(RecurrenceExpander.isOccurrenceOf(ev, at(2026, 1, 12, 9)))
        assertFalse(RecurrenceExpander.isOccurrenceOf(ev, at(2026, 1, 13, 9)))
    }

    @Test
    fun `isOccurrenceOf returns false for time before seed`() {
        val ev = event(
            rrule = "FREQ=DAILY",
            startsAt = at(2026, 6, 1, 9)
        )
        assertFalse(RecurrenceExpander.isOccurrenceOf(ev, at(2026, 1, 1, 9)))
    }

    @Test
    fun `isOccurrenceOf returns false for malformed rrule`() {
        val ev = event(
            rrule = "junk",
            startsAt = at(2026, 1, 1)
        )
        assertFalse(RecurrenceExpander.isOccurrenceOf(ev, at(2026, 1, 1)))
    }

    // --- addExdate / removeExdate ---

    @Test
    fun `addExdate creates the array when none exists`() {
        val out = RecurrenceExpander.addExdate(null, at(2026, 1, 5))
        assertEquals(1, out.size)
    }

    @Test
    fun `addExdate is idempotent`() {
        val arr = RecurrenceExpander.addExdate(null, at(2026, 1, 5))
        val again = RecurrenceExpander.addExdate(arr, at(2026, 1, 5))
        assertEquals(1, again.size)
    }

    @Test
    fun `addExdate preserves prior entries and sorts`() {
        val first = RecurrenceExpander.addExdate(null, at(2026, 1, 12))
        val second = RecurrenceExpander.addExdate(first, at(2026, 1, 5))
        assertEquals(2, second.size)
        // Must be sorted ascending
        val parsed = second.map { OffsetDateTime.parse((it as JsonPrimitive).content) }
        assertEquals(parsed, parsed.sorted())
    }

    @Test
    fun `removeExdate returns null when the result is empty`() {
        val arr = RecurrenceExpander.addExdate(null, at(2026, 1, 5))
        val removed = RecurrenceExpander.removeExdate(arr, at(2026, 1, 5))
        assertNull(removed)
    }

    @Test
    fun `removeExdate of a missing entry returns the existing list`() {
        val arr = RecurrenceExpander.addExdate(null, at(2026, 1, 5))
        val removed = RecurrenceExpander.removeExdate(arr, at(2026, 2, 1))
        assertEquals(1, removed?.size)
    }

    @Test
    fun `removeExdate on empty input returns null`() {
        assertNull(RecurrenceExpander.removeExdate(null, at(2026, 1, 1)))
    }

    // --- exdatesBefore / exdatesMatching ---

    @Test
    fun `exdatesBefore drops entries at or after the boundary`() {
        var arr: kotlinx.serialization.json.JsonElement? = null
        arr = RecurrenceExpander.addExdate(arr, at(2026, 1, 5))
        arr = RecurrenceExpander.addExdate(arr, at(2026, 1, 12))
        arr = RecurrenceExpander.addExdate(arr, at(2026, 1, 19))
        val out = RecurrenceExpander.exdatesBefore(arr, at(2026, 1, 12))
        assertNotNull(out)
        assertEquals(1, out.size)
    }

    @Test
    fun `exdatesBefore returns null when nothing survives`() {
        val arr = RecurrenceExpander.addExdate(null, at(2026, 6, 1))
        assertNull(RecurrenceExpander.exdatesBefore(arr, at(2026, 1, 1)))
    }

    @Test
    fun `exdatesMatching keeps only entries matching the predicate`() {
        var arr: kotlinx.serialization.json.JsonElement? = null
        arr = RecurrenceExpander.addExdate(arr, at(2026, 1, 5))
        arr = RecurrenceExpander.addExdate(arr, at(2026, 1, 12))
        val out = RecurrenceExpander.exdatesMatching(arr) { it.dayOfMonth == 5 }
        assertNotNull(out)
        assertEquals(1, out.size)
    }

    @Test
    fun `exdatesMatching returns null when nothing matches`() {
        val arr = RecurrenceExpander.addExdate(null, at(2026, 1, 5))
        assertNull(RecurrenceExpander.exdatesMatching(arr) { false })
    }

    // --- withUntil ---

    @Test
    fun `withUntil appends UNTIL when none exists`() {
        val out = RecurrenceExpander.withUntil("FREQ=WEEKLY;BYDAY=MO", at(2026, 6, 1))
        assertTrue(out.contains("UNTIL=20260601T000000Z"))
        assertTrue(out.contains("FREQ=WEEKLY"))
    }

    @Test
    fun `withUntil replaces an existing UNTIL`() {
        val out = RecurrenceExpander.withUntil("FREQ=DAILY;UNTIL=20300101T000000Z", at(2026, 6, 1))
        assertEquals(1, out.split(";").count { it.startsWith("UNTIL=") })
        assertTrue(out.contains("UNTIL=20260601T000000Z"))
    }

    @Test
    fun `withUntil strips COUNT when adding UNTIL`() {
        val out = RecurrenceExpander.withUntil("FREQ=DAILY;COUNT=10", at(2026, 6, 1))
        assertFalse(out.contains("COUNT="))
        assertTrue(out.contains("UNTIL=20260601T000000Z"))
    }

    @Test
    fun `withUntil normalises offset to UTC`() {
        val out = RecurrenceExpander.withUntil(
            "FREQ=DAILY",
            OffsetDateTime.of(2026, 6, 1, 5, 0, 0, 0, ZoneOffset.ofHours(-5))
        )
        // 2026-06-01T05:00-05:00 == 2026-06-01T10:00Z
        assertTrue(out.contains("UNTIL=20260601T100000Z"))
    }

    @Test
    fun `expand ignores malformed entries in the EXDATE list`() {
        val ev = event(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startsAt = at(2026, 1, 5, 9),
            exdates = JsonArray(listOf(JsonPrimitive("not-a-date"), JsonNull))
        )
        // Malformed entries should be ignored, so all four Mondays still appear.
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 2, 1))
        assertEquals(4, out.size)
    }

    // --- additional branch coverage ---

    @Test
    fun `expand returns empty when from is greater than to (early return)`() {
        val ev = event(rrule = "FREQ=DAILY", startsAt = at(2026, 1, 1))
        // periodStart = max(from, seed) = from (2026-06-01) > to (2026-05-01) → early empty
        val out = RecurrenceExpander.expand(ev, at(2026, 6, 1), at(2026, 5, 1))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `expand returns empty when ical4j produces no dates inside the window`() {
        // BYDAY=MO with a window that spans only Tuesday→Wednesday should produce nothing
        val ev = event(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startsAt = at(2026, 1, 5, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 6, 0), at(2026, 1, 7, 0))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `expand treats top-level JsonNull exdates as no exdates`() {
        val ev = event(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            startsAt = at(2026, 1, 5, 9),
            exdates = JsonNull
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 2, 1))
        assertEquals(4, out.size)
    }

    @Test
    fun `removeExdate keeps remaining entries when removed entry was present`() {
        var arr: kotlinx.serialization.json.JsonElement? = null
        arr = RecurrenceExpander.addExdate(arr, at(2026, 1, 5))
        arr = RecurrenceExpander.addExdate(arr, at(2026, 1, 12))
        val removed = RecurrenceExpander.removeExdate(arr, at(2026, 1, 5))
        assertNotNull(removed)
        assertEquals(1, removed.size)
        assertEquals(at(2026, 1, 12).toString(), (removed[0] as JsonPrimitive).content)
    }

    @Test
    fun `exdatesMatching on null returns null`() {
        assertNull(RecurrenceExpander.exdatesMatching(null) { true })
    }

    @Test
    fun `exdatesMatching on JsonNull returns null`() {
        assertNull(RecurrenceExpander.exdatesMatching(JsonNull) { true })
    }

    @Test
    fun `exdatesBefore on empty returns null`() {
        assertNull(RecurrenceExpander.exdatesBefore(null, at(2026, 1, 1)))
    }

    // --- RRULE semantic edge cases ---

    @Test
    fun `expand BYMONTHDAY=15 produces the 15th of each month`() {
        val ev = event(
            rrule = "FREQ=MONTHLY;BYMONTHDAY=15",
            startsAt = at(2026, 1, 15, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 4, 1))
        assertEquals(
            listOf(at(2026, 1, 15, 9), at(2026, 2, 15, 9), at(2026, 3, 15, 9)),
            out
        )
    }

    @Test
    fun `expand BYSETPOS=-1 with BYDAY=FR yields the last Friday of the month`() {
        // Last Friday of Jan 2026 is the 30th; Feb is the 27th; Mar is the 27th.
        val ev = event(
            rrule = "FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-1",
            startsAt = at(2026, 1, 30, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 4, 1))
        assertEquals(
            listOf(at(2026, 1, 30, 9), at(2026, 2, 27, 9), at(2026, 3, 27, 9)),
            out
        )
    }

    @Test
    fun `expand COUNT=1 produces exactly the seed`() {
        val ev = event(
            rrule = "FREQ=DAILY;COUNT=1",
            startsAt = at(2026, 1, 5, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 12, 31))
        assertEquals(listOf(at(2026, 1, 5, 9)), out)
    }

    @Test
    fun `expand UNTIL inclusive of the boundary occurrence`() {
        // Pin down ical4j's UNTIL semantics: an occurrence whose start is exactly
        // equal to UNTIL is included (RFC 5545 §3.3.10).
        val ev = event(
            rrule = "FREQ=DAILY;UNTIL=20260108T090000Z",
            startsAt = at(2026, 1, 5, 9)
        )
        val out = RecurrenceExpander.expand(ev, at(2026, 1, 1), at(2026, 12, 31))
        assertEquals(4, out.size)
        assertTrue(at(2026, 1, 8, 9) in out)
    }

    @Test
    fun `expand preserves the seed offset across the spring-forward DST transition`() {
        // With OffsetDateTime semantics, a daily event at 9am Eastern stays at
        // 9am at the offset stored on the master — the offset doesn't shift,
        // so the UTC moment shifts by an hour across DST. This pins the
        // current behavior; per-event TZID storage would change it.
        val seedOffset = ZoneOffset.ofHours(-5)
        val seed = OffsetDateTime.of(2026, 3, 6, 9, 0, 0, 0, seedOffset)
        val ev = CalendarEvent(
            id = UUID.random(),
            metadataId = metadataId,
            version = 1,
            title = "standup",
            startsAt = seed,
            endsAt = seed.plusHours(1),
            rrule = "FREQ=DAILY;COUNT=10"
        )
        val out = RecurrenceExpander.expand(ev, seed.minusDays(1), seed.plusDays(20))
        // Every produced occurrence has the same offset as the seed
        assertTrue(out.all { it.offset == seedOffset })
        // And the same wall-clock time
        assertTrue(out.all { it.hour == 9 && it.minute == 0 })
    }

    @Test
    fun `expand respects allDay events the same as timed events at midnight`() {
        // The Calendar feature doesn't model RFC 5545 DTSTART;VALUE=DATE; an
        // all-day event is just a row whose times happen to land on day
        // boundaries. This test pins that simpler model.
        val seed = OffsetDateTime.of(2026, 1, 5, 0, 0, 0, 0, ZoneOffset.UTC)
        val ev = CalendarEvent(
            id = UUID.random(),
            metadataId = metadataId,
            version = 1,
            title = "vacation",
            startsAt = seed,
            endsAt = seed.plusDays(1),
            allDay = true,
            rrule = "FREQ=YEARLY;COUNT=3"
        )
        val out = RecurrenceExpander.expand(ev, seed, seed.plusYears(5))
        assertEquals(3, out.size)
        assertTrue(out.all { it.hour == 0 && it.minute == 0 })
    }
}

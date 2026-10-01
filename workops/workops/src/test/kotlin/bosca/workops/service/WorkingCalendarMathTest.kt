package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.sla.WorkingCalendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkingCalendarMath.addBusinessMinutes] — the pure,
 * stateless calendar arithmetic that decides SLA due-at timestamps.
 * No database, no DI, no TestContainers needed.
 */
class WorkingCalendarMathTest {

    private val calendarId = UUID.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    /**
     * Builds a [WorkingCalendar] from a day-to-ranges map and an
     * optional holiday list. Day names are uppercase DayOfWeek values.
     * Each range is a `startLocal`/`endLocal` pair in HH:MM format.
     */
    private fun calendar(
        timeZone: String = "UTC",
        weeklyHours: Map<String, List<Pair<String, String>>>,
        holidays: List<String> = emptyList(),
    ): WorkingCalendar {
        val weekly = buildJsonObject {
            for ((day, ranges) in weeklyHours) {
                put(day, buildJsonArray {
                    for ((s, e) in ranges) {
                        add(buildJsonObject {
                            put("startLocal", JsonPrimitive(s))
                            put("endLocal", JsonPrimitive(e))
                        })
                    }
                })
            }
        }
        val hols = buildJsonArray {
            for (h in holidays) add(JsonPrimitive(h))
        }
        return WorkingCalendar(
            id = calendarId,
            name = "Test Calendar",
            timeZone = timeZone,
            weeklyHours = weekly,
            holidays = hols,
        )
    }

    /** Mon-Fri 09:00-17:00 UTC calendar — the standard business week. */
    private val monFri9to17 = calendar(
        weeklyHours = mapOf(
            "MONDAY" to listOf("09:00" to "17:00"),
            "TUESDAY" to listOf("09:00" to "17:00"),
            "WEDNESDAY" to listOf("09:00" to "17:00"),
            "THURSDAY" to listOf("09:00" to "17:00"),
            "FRIDAY" to listOf("09:00" to "17:00"),
        )
    )

    // ---- T4.1: Basic addition within a single working day ----

    @Test
    fun `add minutes within a single working day`() {
        // Wednesday 10:00 + 60 business minutes = Wednesday 11:00
        val start = OffsetDateTime.parse("2026-05-06T10:00:00Z") // Wednesday
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 60)
        assertEquals(OffsetDateTime.parse("2026-05-06T11:00:00Z"), result)
    }

    @Test
    fun `add minutes starting at beginning of working day`() {
        // Monday 09:00 + 120 minutes = Monday 11:00
        val start = OffsetDateTime.parse("2026-05-04T09:00:00Z") // Monday
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 120)
        assertEquals(OffsetDateTime.parse("2026-05-04T11:00:00Z"), result)
    }

    @Test
    fun `zero minutes returns the same instant`() {
        val start = OffsetDateTime.parse("2026-05-04T10:00:00Z")
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 0)
        assertEquals(start, result)
    }

    @Test
    fun `negative minutes returns the same instant`() {
        val start = OffsetDateTime.parse("2026-05-04T10:00:00Z")
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, -5)
        assertEquals(start, result)
    }

    // ---- T4.2: Spanning across non-working periods ----

    @Test
    fun `minutes wrap overnight to next working morning`() {
        // Monday 16:00 + 120 minutes: 60 minutes left on Monday (16:00-17:00),
        // then 60 minutes on Tuesday => Tuesday 10:00
        val start = OffsetDateTime.parse("2026-05-04T16:00:00Z") // Monday
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 120)
        assertEquals(OffsetDateTime.parse("2026-05-05T10:00:00Z"), result)
    }

    @Test
    fun `minutes wrap over weekend from Friday to Monday`() {
        // Friday 16:00 + 120 minutes: 60 minutes Friday (16:00-17:00),
        // Saturday/Sunday skipped, 60 minutes Monday => Monday 10:00
        val start = OffsetDateTime.parse("2026-05-08T16:00:00Z") // Friday
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 120)
        assertEquals(OffsetDateTime.parse("2026-05-11T10:00:00Z"), result)
    }

    @Test
    fun `start outside working hours snaps forward to next window`() {
        // Saturday 12:00 + 60 minutes => Monday 10:00
        val start = OffsetDateTime.parse("2026-05-09T12:00:00Z") // Saturday
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 60)
        assertEquals(OffsetDateTime.parse("2026-05-11T10:00:00Z"), result)
    }

    @Test
    fun `start before working hours on a working day snaps to window start`() {
        // Monday 07:00 + 60 minutes => Monday 10:00 (snaps to 09:00 then +60)
        val start = OffsetDateTime.parse("2026-05-04T07:00:00Z") // Monday
        val result = WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 60)
        assertEquals(OffsetDateTime.parse("2026-05-04T10:00:00Z"), result)
    }

    @Test
    fun `start exactly at a window end advances to the next working window`() {
        val start = OffsetDateTime.parse("2026-05-04T17:00:00Z")

        assertEquals(
            OffsetDateTime.parse("2026-05-05T10:00:00Z"),
            WorkingCalendarMath.addBusinessMinutes(monFri9to17, start, 60),
        )
    }

    // ---- T4.3: Holiday skipping ----

    @Test
    fun `holiday in the middle of the week is skipped`() {
        // Tuesday is a holiday. Monday 16:00 + 120 minutes:
        // 60 min Monday (16:00-17:00), Tuesday skipped,
        // 60 min Wednesday => Wednesday 10:00
        val cal = calendar(
            weeklyHours = mapOf(
                "MONDAY" to listOf("09:00" to "17:00"),
                "TUESDAY" to listOf("09:00" to "17:00"),
                "WEDNESDAY" to listOf("09:00" to "17:00"),
                "THURSDAY" to listOf("09:00" to "17:00"),
                "FRIDAY" to listOf("09:00" to "17:00"),
            ),
            holidays = listOf("2026-05-05"), // Tuesday
        )
        val start = OffsetDateTime.parse("2026-05-04T16:00:00Z") // Monday
        val result = WorkingCalendarMath.addBusinessMinutes(cal, start, 120)
        assertEquals(OffsetDateTime.parse("2026-05-06T10:00:00Z"), result)
    }

    @Test
    fun `starting on a holiday day snaps forward past it`() {
        val cal = calendar(
            weeklyHours = mapOf(
                "MONDAY" to listOf("09:00" to "17:00"),
                "TUESDAY" to listOf("09:00" to "17:00"),
                "WEDNESDAY" to listOf("09:00" to "17:00"),
                "THURSDAY" to listOf("09:00" to "17:00"),
                "FRIDAY" to listOf("09:00" to "17:00"),
            ),
            holidays = listOf("2026-05-04"), // Monday
        )
        // Monday is holiday, 60 minutes => Tuesday 10:00
        val start = OffsetDateTime.parse("2026-05-04T10:00:00Z") // Monday (holiday)
        val result = WorkingCalendarMath.addBusinessMinutes(cal, start, 60)
        assertEquals(OffsetDateTime.parse("2026-05-05T10:00:00Z"), result)
    }

    // ---- T4.4: Multi-range daily windows (lunch break) ----

    @Test
    fun `lunch break splits the day into two ranges`() {
        // 09:00-12:00, 13:00-17:00 (lunch 12-13)
        val cal = calendar(
            weeklyHours = mapOf(
                "MONDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "TUESDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "WEDNESDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "THURSDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "FRIDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
            ),
        )
        // Monday 11:00 + 120 minutes:
        // 60 min in morning range (11:00-12:00), skip lunch,
        // 60 min in afternoon range (13:00-14:00) => Monday 14:00
        val start = OffsetDateTime.parse("2026-05-04T11:00:00Z") // Monday
        val result = WorkingCalendarMath.addBusinessMinutes(cal, start, 120)
        assertEquals(OffsetDateTime.parse("2026-05-04T14:00:00Z"), result)
    }

    @Test
    fun `lunch break with wrap to next day`() {
        val cal = calendar(
            weeklyHours = mapOf(
                "MONDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "TUESDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "WEDNESDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "THURSDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "FRIDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
            ),
        )
        // Monday 16:00 + 120 minutes:
        // 60 min Mon afternoon (16:00-17:00),
        // 60 min Tue morning (09:00-10:00) => Tuesday 10:00
        val start = OffsetDateTime.parse("2026-05-04T16:00:00Z") // Monday
        val result = WorkingCalendarMath.addBusinessMinutes(cal, start, 120)
        assertEquals(OffsetDateTime.parse("2026-05-05T10:00:00Z"), result)
    }

    @Test
    fun `cursor during lunch snaps to afternoon range`() {
        val cal = calendar(
            weeklyHours = mapOf(
                "MONDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "TUESDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "WEDNESDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "THURSDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
                "FRIDAY" to listOf("09:00" to "12:00", "13:00" to "17:00"),
            ),
        )
        // Monday 12:30 + 60 minutes:
        // cursor is after morning range end, before afternoon start;
        // snaps to 13:00 => 60 min => 14:00
        val start = OffsetDateTime.parse("2026-05-04T12:30:00Z") // Monday
        val result = WorkingCalendarMath.addBusinessMinutes(cal, start, 60)
        assertEquals(OffsetDateTime.parse("2026-05-04T14:00:00Z"), result)
    }

    // ---- T4.5: 370-iteration safety cap ----

    @Test
    fun `safety cap terminates after 370 iterations for impossible calendar`() {
        // An empty weekly-hours calendar has zero working time per day.
        // The loop iterates 370 days and returns wherever the cursor landed.
        val emptyCal = calendar(weeklyHours = emptyMap())
        val start = OffsetDateTime.parse("2026-05-04T10:00:00Z")
        val result = WorkingCalendarMath.addBusinessMinutes(emptyCal, start, 60)
        // The cursor should have advanced 370 days from the start date's
        // beginning-of-day since each iteration moves one day forward.
        val expected = OffsetDateTime.parse("2027-05-09T00:00:00Z") // 370 days from 2026-05-04
        assertEquals(expected, result)
    }

    // ---- Continuous (24x7) calendar short-circuits ----

    @Test
    fun `continuous 24x7 calendar adds wall-clock minutes directly`() {
        val cal = calendar(
            weeklyHours = mapOf(
                "MONDAY" to listOf("00:00" to "24:00"),
                "TUESDAY" to listOf("00:00" to "24:00"),
                "WEDNESDAY" to listOf("00:00" to "24:00"),
                "THURSDAY" to listOf("00:00" to "24:00"),
                "FRIDAY" to listOf("00:00" to "24:00"),
                "SATURDAY" to listOf("00:00" to "24:00"),
                "SUNDAY" to listOf("00:00" to "24:00"),
            ),
        )
        val start = OffsetDateTime.parse("2026-05-04T10:00:00Z")
        val result = WorkingCalendarMath.addBusinessMinutes(cal, start, 90)
        assertEquals(start.plusMinutes(90), result)
    }

    @Test
    fun `continuous calendar also accepts an explicit end of day timestamp`() {
        val everyDay = java.time.DayOfWeek.entries.associate { day ->
            day.name to listOf("00:00" to "23:59:59.999999999")
        }
        val cal = calendar(weeklyHours = everyDay)
        val start = OffsetDateTime.parse("2026-05-04T10:00:00Z")

        assertEquals(start.plusMinutes(45), WorkingCalendarMath.addBusinessMinutes(cal, start, 45))
    }

    @Test
    fun `seven day calendar with ordinary windows is not treated as continuous`() {
        val everyDay = java.time.DayOfWeek.entries.associate { day ->
            day.name to listOf("09:00" to "17:00")
        }
        val cal = calendar(weeklyHours = everyDay)
        val start = OffsetDateTime.parse("2026-05-04T16:30:00Z")

        assertEquals(
            OffsetDateTime.parse("2026-05-05T09:30:00Z"),
            WorkingCalendarMath.addBusinessMinutes(cal, start, 60),
        )
    }

    @Test
    fun `malformed weekly entries and holidays are ignored while valid values remain effective`() {
        val weekly = buildJsonObject {
            put("not-a-day", buildJsonArray {})
            put("TUESDAY", JsonPrimitive("not-an-array"))
            put("WEDNESDAY", buildJsonArray {
                add(JsonPrimitive("not-an-object"))
                add(buildJsonObject { put("endLocal", JsonPrimitive("17:00")) })
                add(buildJsonObject { put("startLocal", JsonPrimitive("09:00")) })
                add(buildJsonObject {
                    put("startLocal", buildJsonArray { })
                    put("endLocal", JsonPrimitive("17:00"))
                })
                add(buildJsonObject {
                    put("startLocal", JsonPrimitive("09:00"))
                    put("endLocal", buildJsonArray { })
                })
                add(buildJsonObject {
                    put("startLocal", JsonPrimitive("invalid"))
                    put("endLocal", JsonPrimitive("17:00"))
                })
                add(buildJsonObject {
                    put("startLocal", JsonPrimitive("09:00"))
                    put("endLocal", JsonPrimitive("17:00"))
                })
            })
        }
        val holidays = buildJsonArray {
            add(buildJsonObject {})
            add(buildJsonArray { })
            add(JsonPrimitive("not-a-date"))
            add(JsonPrimitive("2026-05-07"))
        }
        val cal = WorkingCalendar(
            id = calendarId,
            name = "Tolerant calendar",
            timeZone = "UTC",
            weeklyHours = weekly,
            holidays = holidays,
        )

        assertEquals(
            OffsetDateTime.parse("2026-05-06T10:00:00Z"),
            WorkingCalendarMath.addBusinessMinutes(
                cal,
                OffsetDateTime.parse("2026-05-06T09:00:00Z"),
                60,
            ),
        )
    }

    @Test
    fun `non collection calendar values behave as an empty calendar`() {
        val cal = WorkingCalendar(
            id = calendarId,
            name = "Empty calendar",
            timeZone = "UTC",
            weeklyHours = JsonPrimitive("none"),
            holidays = JsonPrimitive("none"),
        )
        val start = OffsetDateTime.parse("2026-05-04T10:00:00Z")

        assertEquals(
            OffsetDateTime.parse("2027-05-09T00:00:00Z"),
            WorkingCalendarMath.addBusinessMinutes(cal, start, 1),
        )
    }

    @Test
    fun `invalid timezone is rejected with calendar context`() {
        val cal = calendar(timeZone = "Mars/Olympus", weeklyHours = emptyMap())

        val failure = assertFailsWith<IllegalStateException> {
            WorkingCalendarMath.addBusinessMinutes(
                cal,
                OffsetDateTime.parse("2026-05-04T10:00:00Z"),
                1,
            )
        }

        assertTrue(calendarId.toString() in (failure.message ?: ""))
        assertTrue("Mars/Olympus" in (failure.message ?: ""))
    }
}

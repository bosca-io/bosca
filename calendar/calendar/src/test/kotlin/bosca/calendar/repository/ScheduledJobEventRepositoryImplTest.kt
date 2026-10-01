package bosca.calendar.repository

import bosca.serialization.UUID
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledJobEventRepositoryImplTest {

    private val repo = ScheduledJobEventRepositoryImpl()
    private val serverZone = ZoneId.systemDefault()

    private val from = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    private val to = OffsetDateTime.of(2026, 4, 2, 0, 0, 0, 0, ZoneOffset.UTC)
    private val jobId = UUID.random()

    private fun serverHour(utcHour: Int): Int {
        val utc = OffsetDateTime.of(2026, 4, 1, utcHour, 0, 0, 0, ZoneOffset.UTC)
        return utc.toInstant().atZone(serverZone).hour
    }

    @Test
    fun `expands hourly cron into 24 occurrences over a single day`() {
        val jobs = listOf(
            ScheduledJobEventRepositoryImpl.JobRow(jobId, "Hourly Job", "runs every hour", "0 * * * *")
        )
        val result = repo.expandOccurrences(jobs, emptyList(), from, to)

        assertEquals(24, result.size)
        result.forEach {
            assertEquals("Hourly Job", it.title)
            assertFalse(it.completed)
        }
    }

    @Test
    fun `marks cron occurrence as completed when history matches`() {
        val completedServerHour = 10
        val jobs = listOf(
            ScheduledJobEventRepositoryImpl.JobRow(jobId, "Job", "", "0 * * * *")
        )
        val expectedUtc = java.time.LocalDateTime.of(2026, 4, 1, completedServerHour, 0)
            .atZone(serverZone)
            .toInstant()
            .atOffset(ZoneOffset.UTC)

        val history = listOf(
            ScheduledJobEventRepositoryImpl.HistoryRow(jobId, expectedUtc)
        )

        val result = repo.expandOccurrences(jobs, history, from, to)

        val matched = result.first { it.startsAt.toInstant() == expectedUtc.toInstant() }
        assertTrue(matched.completed)

        val others = result.filter { it.startsAt.toInstant() != expectedUtc.toInstant() }
        assertTrue(others.isNotEmpty())
        others.forEach { assertFalse(it.completed) }
    }

    @Test
    fun `includes historical runs that do not match current cron`() {
        val targetServerHour = 12
        val cronExpr = "0 $targetServerHour * * *"
        val jobs = listOf(
            ScheduledJobEventRepositoryImpl.JobRow(jobId, "Job", "", cronExpr)
        )
        val manualRun = OffsetDateTime.of(2026, 4, 1, 3, 30, 0, 0, ZoneOffset.UTC)
        val history = listOf(
            ScheduledJobEventRepositoryImpl.HistoryRow(jobId, manualRun)
        )

        val result = repo.expandOccurrences(jobs, history, from, to)

        assertEquals(2, result.size)
        val cronEvent = result.first { !it.completed }
        val histEvent = result.first { it.completed }
        assertEquals(manualRun.toInstant(), histEvent.startsAt.toInstant())
        assertTrue(cronEvent.startsAt.toInstant() != manualRun.toInstant())
    }

    @Test
    fun `includes history for deleted or disabled jobs`() {
        val deletedJobId = UUID.random()
        val historyTime = OffsetDateTime.of(2026, 4, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        val history = listOf(
            ScheduledJobEventRepositoryImpl.HistoryRow(deletedJobId, historyTime)
        )

        val result = repo.expandOccurrences(emptyList(), history, from, to)

        assertEquals(1, result.size)
        assertEquals("Deleted job", result[0].title)
        assertTrue(result[0].completed)
    }

    @Test
    fun `returns empty list for jobs with invalid cron`() {
        val jobs = listOf(
            ScheduledJobEventRepositoryImpl.JobRow(jobId, "Bad Job", "", "not-a-cron")
        )
        val result = repo.expandOccurrences(jobs, emptyList(), from, to)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `results are sorted by startsAt`() {
        val jobs = listOf(
            ScheduledJobEventRepositoryImpl.JobRow(jobId, "Job", "", "0 */6 * * *")
        )
        val result = repo.expandOccurrences(jobs, emptyList(), from, to)

        val instants = result.map { it.startsAt.toInstant() }
        assertEquals(instants.sorted(), instants)
    }

    @Test
    fun `daily job produces single occurrence in one-day range`() {
        val targetServerHour = 9
        val cronExpr = "30 $targetServerHour * * *"
        val jobs = listOf(
            ScheduledJobEventRepositoryImpl.JobRow(jobId, "Daily", "", cronExpr)
        )
        val result = repo.expandOccurrences(jobs, emptyList(), from, to)

        assertEquals(1, result.size)
        val localTime = result[0].startsAt.toInstant().atZone(serverZone)
        assertEquals(targetServerHour, localTime.hour)
        assertEquals(30, localTime.minute)
    }

    @Test
    fun `each occurrence gets a unique deterministic id`() {
        val jobs = listOf(
            ScheduledJobEventRepositoryImpl.JobRow(jobId, "Job", "", "0 * * * *")
        )
        val result = repo.expandOccurrences(jobs, emptyList(), from, to)

        val ids = result.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "All occurrence IDs must be unique")

        val result2 = repo.expandOccurrences(jobs, emptyList(), from, to)
        assertEquals(result.map { it.id }, result2.map { it.id }, "IDs must be deterministic")
    }

    @Test
    fun `cron occurrences match scheduler runner evaluation`() {
        val cron = bosca.scheduler.cron.CronExpression.parse("0 9 * * *")
        val now = OffsetDateTime.now()
        val schedulerNext = cron.nextExecution(now)!!

        val wideTo = now.plusDays(2)
        val result = repo.expandOccurrences(
            listOf(ScheduledJobEventRepositoryImpl.JobRow(jobId, "Job", "", "0 9 * * *")),
            emptyList(),
            now,
            wideTo
        )

        val firstOccurrence = result.first()
        assertEquals(
            schedulerNext.toInstant().epochSecond / 60,
            firstOccurrence.startsAt.toInstant().epochSecond / 60,
            "Calendar expansion should produce the same instant as SchedulerRunner"
        )
    }
}

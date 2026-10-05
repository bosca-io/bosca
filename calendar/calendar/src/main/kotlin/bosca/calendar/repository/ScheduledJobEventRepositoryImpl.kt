package bosca.calendar.repository

import bosca.db.DatabaseDispatcher
import bosca.db.connection
import bosca.scheduler.cron.CronExpression
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.nio.ByteBuffer
import kotlin.uuid.Uuid

/**
 * Expands each enabled scheduled job's cron expression into individual
 * occurrences within the requested range, then overlays completed
 * executions from `scheduler.job_history`. This replaces the earlier
 * SQL-only approach that could only surface the single `next_run_at`.
 */
class ScheduledJobEventRepositoryImpl : ScheduledJobEventRepository {

    data class JobRow(
        val id: UUID,
        val name: String,
        val description: String,
        val cronExpression: String,
    )

    data class HistoryRow(
        val scheduledJobId: UUID,
        val scheduledFor: OffsetDateTime,
    )

    override suspend fun getInRange(from: OffsetDateTime, to: OffsetDateTime): List<SyntheticEvent> =
        withContext(DatabaseDispatcher) {
            val jobs = fetchEnabledJobs()
            val history = fetchHistory(from, to)
            expandOccurrences(jobs, history, from, to)
        }

    private suspend fun fetchEnabledJobs(): List<JobRow> {
        val connection = connection()
        return connection.useStatement(
            """
            SELECT id, name, coalesce(description, '') as description, cron_expression
            FROM scheduler.scheduled_jobs
            WHERE enabled = true AND cron_expression IS NOT NULL
            """.trimIndent()
        ) { stmt ->
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<JobRow>()
                while (rs.next()) {
                    results.add(
                        JobRow(
                            id = rs.getObject("id", java.util.UUID::class.java).let { UUID.parse(it.toString()) },
                            name = rs.getString("name"),
                            description = rs.getString("description"),
                            cronExpression = rs.getString("cron_expression"),
                        )
                    )
                }
                results
            }
        }
    }

    private suspend fun fetchHistory(from: OffsetDateTime, to: OffsetDateTime): List<HistoryRow> {
        val connection = connection()
        return connection.useStatement(
            """
            SELECT DISTINCT ON (scheduled_job_id, scheduled_for)
                scheduled_job_id, scheduled_for
            FROM scheduler.job_history
            WHERE scheduled_job_id IS NOT NULL
              AND scheduled_for >= ?
              AND scheduled_for < ?
            ORDER BY scheduled_job_id, scheduled_for, triggered_at DESC
            """.trimIndent()
        ) { stmt ->
            stmt.setObject(1, from)
            stmt.setObject(2, to)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<HistoryRow>()
                while (rs.next()) {
                    results.add(
                        HistoryRow(
                            scheduledJobId = rs.getObject("scheduled_job_id", java.util.UUID::class.java)
                                .let { UUID.parse(it.toString()) },
                            scheduledFor = rs.getObject("scheduled_for", java.time.OffsetDateTime::class.java),
                        )
                    )
                }
                results
            }
        }
    }

    internal fun expandOccurrences(
        jobs: List<JobRow>,
        history: List<HistoryRow>,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ): List<SyntheticEvent> {
        val historyByJob = history.groupBy { it.scheduledJobId }
        val events = mutableListOf<SyntheticEvent>()

        for (job in jobs) {
            val cron = CronExpression.tryParse(job.cronExpression) ?: continue
            val jobHistory = historyByJob[job.id]
                ?.map { minuteKey(it.scheduledFor) }
                ?.toSet()
                ?: emptySet()

            val occurrences = generateOccurrences(cron, from, to)
            for (occurrence in occurrences) {
                events.add(
                    SyntheticEvent(
                        id = occurrenceId(job.id, occurrence),
                        title = job.name,
                        description = job.description,
                        startsAt = occurrence,
                        endsAt = occurrence.plusMinutes(15),
                        completed = minuteKey(occurrence) in jobHistory,
                    )
                )
            }

            val cronTimes = occurrences.map { minuteKey(it) }.toSet()
            for (hist in historyByJob[job.id] ?: emptyList()) {
                if (minuteKey(hist.scheduledFor) !in cronTimes) {
                    val utc = hist.scheduledFor.toInstant().atOffset(ZoneOffset.UTC)
                    events.add(
                        SyntheticEvent(
                            id = occurrenceId(job.id, utc),
                            title = job.name,
                            description = job.description,
                            startsAt = utc,
                            endsAt = utc.plusMinutes(15),
                            completed = true,
                        )
                    )
                }
            }
        }

        val knownJobIds = jobs.map { it.id }.toSet()
        for (hist in history) {
            if (hist.scheduledJobId !in knownJobIds) {
                val utc = hist.scheduledFor.toInstant().atOffset(ZoneOffset.UTC)
                events.add(
                    SyntheticEvent(
                        id = occurrenceId(hist.scheduledJobId, utc),
                        title = "Deleted job",
                        description = "",
                        startsAt = utc,
                        endsAt = utc.plusMinutes(15),
                        completed = true,
                    )
                )
            }
        }

        return events.sortedBy { it.startsAt }
    }

    private fun generateOccurrences(
        cron: CronExpression,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ): List<OffsetDateTime> {
        val serverZone = ZoneId.systemDefault()
        val fromLocal = from.toInstant().atZone(serverZone).toOffsetDateTime()
        val toLocal = to.toInstant().atZone(serverZone).toOffsetDateTime()

        val results = mutableListOf<OffsetDateTime>()
        var cursor = fromLocal.minusMinutes(1)
        val maxOccurrences = 500
        while (results.size < maxOccurrences) {
            val next = cron.nextExecution(cursor) ?: break
            if (next >= toLocal) break
            results.add(next.toInstant().atOffset(ZoneOffset.UTC))
            cursor = next
        }
        return results
    }

    private fun occurrenceId(jobId: UUID, at: OffsetDateTime): UUID {
        val buf = ByteBuffer.allocate(24)
        val jid = java.util.UUID.fromString(jobId.toString())
        buf.putLong(jid.mostSignificantBits)
        buf.putLong(jid.leastSignificantBits)
        buf.putLong(at.toInstant().truncatedTo(ChronoUnit.MINUTES).toEpochMilli())
        return Uuid.parse(java.util.UUID.nameUUIDFromBytes(buf.array()).toString())
    }

    private fun minuteKey(dt: OffsetDateTime): Long =
        dt.toInstant().truncatedTo(ChronoUnit.MINUTES).toEpochMilli()
}

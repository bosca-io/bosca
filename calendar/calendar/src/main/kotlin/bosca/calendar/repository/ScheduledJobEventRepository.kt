package bosca.calendar.repository

import bosca.serialization.OffsetDateTime

interface ScheduledJobEventRepository {

    /**
     * Returns scheduled job events whose trigger time falls inside the
     * half-open range `[from, to)`. Future occurrences are expanded from
     * each enabled job's cron expression; past executions come from
     * `scheduler.job_history`. When a cron occurrence matches a historical
     * execution, it is marked as completed.
     */
    suspend fun getInRange(from: OffsetDateTime, to: OffsetDateTime): List<SyntheticEvent>
}

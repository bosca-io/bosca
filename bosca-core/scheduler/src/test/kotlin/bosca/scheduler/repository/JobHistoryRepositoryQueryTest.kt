package bosca.scheduler.repository

import bosca.db.annotation.Query
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JobHistoryRepositoryQueryTest {

    private fun querySql(methodName: String): String {
        // Java reflection (instead of kotlin.reflect.full) so we don't pull kotlin-reflect
        // into the scheduler module's test classpath just to read one annotation value.
        val method = JobHistoryRepository::class.java.declaredMethods.firstOrNull { it.name == methodName }
        assertNotNull(method, "method $methodName not found on JobHistoryRepository")
        val query = method.getAnnotation(Query::class.java)
        assertNotNull(query, "method $methodName is missing @Query annotation")
        return query.value
    }

    /**
     * Regression: `refreshPendingRow` previously rewrote `triggered_at = NOW()` on every
     * re-enqueue, which made `cleanupStaleExecutions` (and the `countStaleRecords` check it
     * piggybacks on) blind to jobs stuck in a `DelayException` bounce loop — `triggered_at`
     * never aged past the cleanup cutoff. The fix is to leave `triggered_at` alone so it
     * keeps pointing at the original dispatch time.
     *
     * Asserted at the SQL level because this is what the runtime executes; a Kotlin-side
     * helper could be refactored away without anyone noticing the SQL also changed.
     */
    @Test
    fun `refreshPendingRow SQL must not reset triggered_at`() {
        val sql = querySql("refreshPendingRow")
        val setClause = extractSetClause(sql)
        assertFalse(
            setClause.contains("triggered_at", ignoreCase = true),
            "refreshPendingRow must not assign triggered_at — that would mask stuck jobs from cleanupStaleExecutions. SET clause was: $setClause"
        )
    }

    /**
     * Companion guardrail: the fields `refreshPendingRow` *is* supposed to update must stay
     * in the SET clause. Catches the opposite regression (over-aggressive deletion of the
     * SET assignments while removing `triggered_at`).
     */
    @Test
    fun `refreshPendingRow SQL still updates scheduled_for, delayed_until, status`() {
        val setClause = extractSetClause(querySql("refreshPendingRow")).lowercase()
        for (column in listOf("scheduled_for", "delayed_until", "status", "completed_at", "error_message")) {
            assertTrue(
                setClause.contains(column),
                "refreshPendingRow must continue to update $column. SET clause was: $setClause"
            )
        }
    }

    /**
     * Regression for the SKIPPED bug: the by-PK update path must match on `id`, never on
     * `job_id`. If a future refactor "simplified" `updateStatusById` to match on `job_id`,
     * SKIPPED rows (whose `job_id` is `UUID.NIL`) would silently fail to update, exactly
     * as before the fix.
     */
    @Test
    fun `updateStatusById SQL matches on primary key, not job_id`() {
        val sql = querySql("updateStatusById").lowercase()
        val whereClause = sql.substringAfter("where", missingDelimiterValue = "")
        assertTrue(whereClause.contains("id = :id"), "updateStatusById WHERE must match by primary key id. SQL was: $sql")
        assertFalse(
            Regex("""\bjob_id\s*=""").containsMatchIn(whereClause),
            "updateStatusById WHERE must NOT match by job_id (that's updateStatus's job). SQL was: $sql"
        )
    }

    /**
     * Returns the substring of [sql] strictly between `SET` and the next `WHERE`. Matches
     * the keywords case-insensitively and tolerates any whitespace around them (the @Query
     * SQL uses newlines, not single spaces).
     */
    private fun extractSetClause(sql: String): String {
        val setMatch = Regex("""(?i)\bset\b""").find(sql)
        val whereMatch = setMatch?.let { Regex("""(?i)\bwhere\b""").find(sql, startIndex = it.range.last + 1) }
        check(setMatch != null && whereMatch != null) { "could not locate SET..WHERE in SQL: $sql" }
        return sql.substring(setMatch.range.last + 1, whereMatch.range.first)
    }
}

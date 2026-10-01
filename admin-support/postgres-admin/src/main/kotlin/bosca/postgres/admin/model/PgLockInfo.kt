package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Information about a database lock from `pg_locks` joined with `pg_stat_activity`,
 * showing the lock holder or waiter along with the associated query and wait duration
 * to diagnose contention and blocking chains.
 */
data class PgLockInfo(
    val pid: Int,
    @ColumnName("lock_type") val lockType: String,
    @ColumnName("database_name") val databaseName: String?,
    @ColumnName("relation_name") val relationName: String?,
    val mode: String,
    val granted: Boolean,
    val query: String?,
    val state: String?,
    @ColumnName("duration_seconds") val durationSeconds: Float?,
)

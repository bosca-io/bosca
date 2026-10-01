package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Represents a long-running transaction from `pg_stat_activity`, particularly sessions
 * in the `idle in transaction` state that hold locks and prevent autovacuum from
 * reclaiming dead tuples. These are distinct from active queries because the transaction
 * remains open even though the backend is not actively executing a statement.
 */
data class PgLongTransaction(
    val pid: Int,
    @ColumnName("database_name") val databaseName: String?,
    @ColumnName("user_name") val userName: String?,
    @ColumnName("application_name") val applicationName: String?,
    val state: String?,
    @ColumnName("xact_start_time") val xactStartTime: String?,
    @ColumnName("xact_duration_seconds") val xactDurationSeconds: Float?,
    @ColumnName("last_query") val lastQuery: String?,
    @ColumnName("wait_event_type") val waitEventType: String?,
    @ColumnName("wait_event") val waitEvent: String?,
)

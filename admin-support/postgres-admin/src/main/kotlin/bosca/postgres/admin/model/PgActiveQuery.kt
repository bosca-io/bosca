package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Represents a currently running or recently completed query from `pg_stat_activity`,
 * including the backend process details, query text, timing, and wait event information
 * for diagnosing long-running or blocked queries.
 */
data class PgActiveQuery(
    val pid: Int,
    @ColumnName("database_name") val databaseName: String?,
    @ColumnName("user_name") val userName: String?,
    @ColumnName("application_name") val applicationName: String?,
    @ColumnName("client_addr") val clientAddr: String?,
    val state: String?,
    val query: String?,
    @ColumnName("query_start") val queryStart: String?,
    @ColumnName("state_change") val stateChange: String?,
    @ColumnName("xact_start") val xactStart: String?,
    @ColumnName("query_duration_seconds") val queryDurationSeconds: Float?,
    @ColumnName("wait_event_type") val waitEventType: String?,
    @ColumnName("wait_event") val waitEvent: String?,
    @ColumnName("backend_start") val backendStart: String?,
    @ColumnName("backend_type") val backendType: String?,
)

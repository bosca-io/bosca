package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Streaming replication state for a connected standby from `pg_stat_replication`,
 * showing the replica's identity, WAL positions, replication lag, and synchronous
 * replication configuration to monitor cluster health and data consistency.
 */
data class PgReplicationStatus(
    val pid: Int,
    @ColumnName("user_name") val userName: String?,
    @ColumnName("application_name") val applicationName: String?,
    @ColumnName("client_addr") val clientAddr: String?,
    val state: String?,
    @ColumnName("sent_lsn") val sentLsn: String?,
    @ColumnName("write_lsn") val writeLsn: String?,
    @ColumnName("flush_lsn") val flushLsn: String?,
    @ColumnName("replay_lsn") val replayLsn: String?,
    @ColumnName("replay_lag_seconds") val replayLagSeconds: Float?,
    @ColumnName("write_lag_seconds") val writeLagSeconds: Float?,
    @ColumnName("flush_lag_seconds") val flushLagSeconds: Float?,
    @ColumnName("sync_state") val syncState: String?,
    @ColumnName("sync_priority") val syncPriority: Int?,
)

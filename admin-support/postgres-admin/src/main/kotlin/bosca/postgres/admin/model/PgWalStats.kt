package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Write-Ahead Log (WAL) generation and I/O statistics from `pg_stat_wal`, providing
 * insight into WAL write throughput, buffer efficiency, and sync overhead to help
 * tune WAL-related configuration parameters like `wal_buffers` and `wal_writer_delay`.
 */
data class PgWalStats(
    @ColumnName("wal_records") val walRecords: Long,
    @ColumnName("wal_fpi") val walFpi: Long,
    @ColumnName("wal_bytes") val walBytes: Long,
    @ColumnName("wal_buffers_full") val walBuffersFull: Long,
    @ColumnName("wal_write") val walWrite: Long,
    @ColumnName("wal_sync") val walSync: Long,
    @ColumnName("wal_write_time") val walWriteTime: Double,
    @ColumnName("wal_sync_time") val walSyncTime: Double,
    @ColumnName("stats_reset") val statsReset: String?,
)

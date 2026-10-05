package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Overall database statistics aggregated from `pg_stat_database` for the connected database,
 * providing insight into transaction throughput, I/O patterns, cache effectiveness, and
 * potential issues like deadlocks and conflicts.
 */
data class PgDatabaseStats(
    @ColumnName("database_name") val databaseName: String,
    @ColumnName("numbackends") val numBackends: Int,
    @ColumnName("xact_commit") val xactCommit: Long,
    @ColumnName("xact_rollback") val xactRollback: Long,
    @ColumnName("blks_read") val blksRead: Long,
    @ColumnName("blks_hit") val blksHit: Long,
    @ColumnName("tup_returned") val tupReturned: Long,
    @ColumnName("tup_fetched") val tupFetched: Long,
    @ColumnName("tup_inserted") val tupInserted: Long,
    @ColumnName("tup_updated") val tupUpdated: Long,
    @ColumnName("tup_deleted") val tupDeleted: Long,
    val conflicts: Long,
    @ColumnName("temp_files") val tempFiles: Long,
    @ColumnName("temp_bytes") val tempBytes: Long,
    val deadlocks: Long,
    @ColumnName("cache_hit_ratio") val cacheHitRatio: Float,
    @ColumnName("database_size") val databaseSize: Long,
    @ColumnName("stats_reset") val statsReset: String?,
)

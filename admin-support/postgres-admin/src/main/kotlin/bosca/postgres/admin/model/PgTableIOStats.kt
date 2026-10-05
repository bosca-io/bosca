package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Per-table I/O statistics from `pg_statio_user_tables`, showing the ratio of disk
 * reads to buffer cache hits for table data, indexes, and TOAST data to identify
 * tables that would benefit from more memory or better caching.
 */
data class PgTableIOStats(
    @ColumnName("schema_name") val schemaName: String,
    @ColumnName("table_name") val tableName: String,
    @ColumnName("heap_blks_read") val heapBlksRead: Long,
    @ColumnName("heap_blks_hit") val heapBlksHit: Long,
    @ColumnName("idx_blks_read") val idxBlksRead: Long,
    @ColumnName("idx_blks_hit") val idxBlksHit: Long,
    @ColumnName("toast_blks_read") val toastBlksRead: Long,
    @ColumnName("toast_blks_hit") val toastBlksHit: Long,
    @ColumnName("cache_hit_ratio") val cacheHitRatio: Float,
)

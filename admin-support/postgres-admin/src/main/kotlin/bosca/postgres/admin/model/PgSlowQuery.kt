package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Aggregated execution statistics for a normalized query from `pg_stat_statements`,
 * showing cumulative timing, call counts, row counts, and cache behavior to identify
 * queries that consume the most database resources.
 */
data class PgSlowQuery(
    val query: String,
    val calls: Long,
    @ColumnName("total_time_ms") val totalTimeMs: Float,
    @ColumnName("mean_time_ms") val meanTimeMs: Float,
    @ColumnName("min_time_ms") val minTimeMs: Float,
    @ColumnName("max_time_ms") val maxTimeMs: Float,
    @ColumnName("stddev_time_ms") val stddevTimeMs: Float,
    val rows: Long,
    @ColumnName("shared_blks_hit") val sharedBlksHit: Long,
    @ColumnName("shared_blks_read") val sharedBlksRead: Long,
    @ColumnName("hit_ratio") val hitRatio: Float,
)

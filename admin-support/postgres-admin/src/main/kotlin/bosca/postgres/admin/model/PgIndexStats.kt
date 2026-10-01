package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Per-index statistics from `pg_stat_user_indexes` combined with index size and definition,
 * showing how frequently each index is scanned and how many rows it returns to identify
 * unused or underperforming indexes.
 */
data class PgIndexStats(
    @ColumnName("schema_name") val schemaName: String,
    @ColumnName("table_name") val tableName: String,
    @ColumnName("index_name") val indexName: String,
    @ColumnName("idx_scan") val idxScan: Long,
    @ColumnName("idx_tup_read") val idxTupRead: Long,
    @ColumnName("idx_tup_fetch") val idxTupFetch: Long,
    @ColumnName("index_size") val indexSize: Long,
    @ColumnName("index_def") val indexDef: String?,
)

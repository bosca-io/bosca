package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Identifies tables where sequential scan frequency and volume suggest that
 * adding an index could significantly improve query performance, based on
 * analysis of `pg_stat_user_tables` scan patterns and table sizes.
 */
data class PgIndexSuggestion(
    @ColumnName("schema_name") val schemaName: String,
    @ColumnName("table_name") val tableName: String,
    @ColumnName("seq_scan") val seqScan: Long,
    @ColumnName("seq_tup_read") val seqTupRead: Long,
    @ColumnName("idx_scan") val idxScan: Long,
    @ColumnName("table_size") val tableSize: Long,
    val reason: String,
)

package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Per-table statistics from `pg_stat_user_tables` combined with table size information,
 * showing scan patterns, row modification counts, maintenance history, and bloat estimates
 * to guide indexing and vacuum decisions.
 */
data class PgTableStats(
    @ColumnName("schema_name") val schemaName: String,
    @ColumnName("table_name") val tableName: String,
    @ColumnName("seq_scan") val seqScan: Long,
    @ColumnName("seq_tup_read") val seqTupRead: Long,
    @ColumnName("idx_scan") val idxScan: Long,
    @ColumnName("idx_tup_fetch") val idxTupFetch: Long,
    @ColumnName("n_tup_ins") val nTupIns: Long,
    @ColumnName("n_tup_upd") val nTupUpd: Long,
    @ColumnName("n_tup_del") val nTupDel: Long,
    @ColumnName("n_tup_hot_upd") val nTupHotUpd: Long,
    @ColumnName("n_live_tup") val nLiveTup: Long,
    @ColumnName("n_dead_tup") val nDeadTup: Long,
    @ColumnName("last_vacuum") val lastVacuum: String?,
    @ColumnName("last_autovacuum") val lastAutovacuum: String?,
    @ColumnName("last_analyze") val lastAnalyze: String?,
    @ColumnName("last_autoanalyze") val lastAutoanalyze: String?,
    @ColumnName("vacuum_count") val vacuumCount: Long,
    @ColumnName("autovacuum_count") val autovacuumCount: Long,
    @ColumnName("analyze_count") val analyzeCount: Long,
    @ColumnName("autoanalyze_count") val autoanalyzeCount: Long,
    @ColumnName("total_size") val totalSize: Long,
    @ColumnName("table_size") val tableSize: Long,
    @ColumnName("index_size") val indexSize: Long,
    @ColumnName("bloat_ratio") val bloatRatio: Float,
)

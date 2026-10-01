package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Progress information for a currently running vacuum operation from
 * `pg_stat_progress_vacuum`, showing the phase, table being vacuumed,
 * and how many blocks and tuples have been processed.
 */
data class PgVacuumProgress(
    val pid: Int,
    @ColumnName("database_name") val databaseName: String?,
    @ColumnName("schema_name") val schemaName: String?,
    @ColumnName("table_name") val tableName: String?,
    val phase: String,
    @ColumnName("heap_blks_total") val heapBlksTotal: Long,
    @ColumnName("heap_blks_scanned") val heapBlksScanned: Long,
    @ColumnName("heap_blks_vacuumed") val heapBlksVacuumed: Long,
    @ColumnName("num_dead_tuples") val numDeadTuples: Long,
)

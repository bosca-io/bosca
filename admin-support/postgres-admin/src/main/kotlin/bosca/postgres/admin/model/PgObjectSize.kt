package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Size information for a database object (table, index, or schema), used to produce
 * a size breakdown of the database by identifying the largest consumers of storage.
 * Includes total size (with indexes and TOAST) and the standalone table data size
 * for comparison.
 */
data class PgObjectSize(
    @ColumnName("schema_name") val schemaName: String,
    @ColumnName("object_name") val objectName: String,
    @ColumnName("object_type") val objectType: String,
    @ColumnName("total_size") val totalSize: Long,
    @ColumnName("table_size") val tableSize: Long?,
    @ColumnName("index_size") val indexSize: Long?,
)

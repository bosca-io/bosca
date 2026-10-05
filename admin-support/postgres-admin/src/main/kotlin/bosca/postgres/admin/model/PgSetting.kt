package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.Ignore

/**
 * A PostgreSQL server configuration parameter from `pg_settings`, including its current
 * value, allowed range, and whether a server restart is required for the new value to
 * take effect.
 */
data class PgSetting(
    val name: String,
    val setting: String,
    val unit: String?,
    val category: String,
    @ColumnName("short_desc") val shortDesc: String,
    val context: String,
    val vartype: String,
    val source: String,
    @ColumnName("min_val") val minVal: String?,
    @ColumnName("max_val") val maxVal: String?,
    @Ignore val enumVals: List<String>?,
    @ColumnName("pending_restart") val pendingRestart: Boolean,
)

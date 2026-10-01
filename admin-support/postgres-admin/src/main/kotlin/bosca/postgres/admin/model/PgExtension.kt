package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Metadata about an installed PostgreSQL extension from `pg_extension` joined with
 * `pg_available_extensions`, showing the current version and whether a newer version
 * is available for upgrade.
 */
data class PgExtension(
    val name: String,
    @ColumnName("installed_version") val installedVersion: String,
    @ColumnName("default_version") val defaultVersion: String?,
    val description: String?,
)

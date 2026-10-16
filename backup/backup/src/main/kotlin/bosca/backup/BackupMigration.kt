package bosca.backup

import bosca.db.migrations.Migration

/** Owns database migrations for per-object backup transfer progress. */
class BackupMigration : Migration {
    override val schema: String = "backup"
    override val resources: List<String> = listOf("V1__backup_object_progress.sql")
}

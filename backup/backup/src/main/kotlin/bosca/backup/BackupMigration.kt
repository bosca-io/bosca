package bosca.backup

import bosca.db.migrations.Migration

/** Owns database migrations for object backup progress and inventory runs. */
class BackupMigration : Migration {
    override val schema: String = "backup"
    override val resources: List<String> = listOf(
        "V1__backup_object_progress.sql",
        "V2__backup_object_runs.sql",
    )
}

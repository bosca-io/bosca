package bosca.backup

import bosca.db.migrations.Migration

/** Owns database migrations for object backup progress and inventory runs. */
class BackupMigration : Migration {
    override val schema: String = "backup"
    override val resources: List<String> = listOf(
        "V1__backup_object_progress.sql",
        "V2__backup_object_runs.sql",
        "V3__object_backup_operations.sql",
        "V4__object_backup_payload_claims.sql",
        "V5__object_backup_storage.sql",
        "V6__object_backup_run_storage.sql",
        "V7__object_backup_store_writers.sql",
        "V8__object_backup_dispatches.sql",
    )
}

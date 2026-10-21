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
        "V9__object_backup_verification.sql",
        "V10__object_backup_capture_plans.sql",
        "V11__object_backup_capture_storage.sql",
        "V12__object_backup_capture_writers.sql",
        "V13__object_backup_discovery.sql",
        "V14__object_backup_restore_requests.sql",
        "V15__postgres_backup_runs.sql",
        "V16__object_backup_verification_observations.sql",
        "V17__object_backup_job_checkpoints.sql",
        "V18__postgres_backup_content_selection.sql",
        "V19__object_backup_content_selection_counts.sql",
        "V20__postgres_backup_content_source.sql",
    )
}

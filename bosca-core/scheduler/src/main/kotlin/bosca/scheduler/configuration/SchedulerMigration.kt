package bosca.scheduler.configuration

import bosca.db.migrations.Migration

class SchedulerMigration : Migration {

    override val schema: String = "scheduler"

    override val resources: List<String> = listOf(
        "V1__scheduler_tables.sql",
        "V2__rename_executions_to_job_history.sql",
        "V3__add_name_to_job_history.sql",
        "V4__add_context_to_job_history.sql",
        "V5__add_delayed_until_to_job_history.sql",
        "V6__add_cancelled_status.sql",
        "V7__add_parent_job_id_to_job_history.sql",
        "V8__add_stale_status.sql",
        "V9__scheduled_job_principals.sql"
    )
}

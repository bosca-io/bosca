package bosca.pipelines.repository

import bosca.db.migrations.Migration

/** Flyway migrations for the `pipelines` schema. */
class PipelinesMigration : Migration {

    override val schema: String = "pipelines"

    override val resources: List<String> = listOf(
        "V1__pipelines.sql",
        "V2__pipeline_triggered.sql",
        "V3__pipeline_run_log_global_index.sql",
        "V4__pipeline_git_sync.sql",
        "V5__pipeline_api.sql",
        "V6__pipeline_run.sql",
        "V7__pipeline_run_result.sql",
        "V8__pipeline_run_node.sql",
        "V9__pipeline_run_log_run_id.sql",
        "V10__unique_active_pipeline_key.sql",
        "V10__pipeline_schedule.sql",
        "V11__pipeline_run_log_outcome_lowercase.sql",
        "V12__pipeline_run_iteration.sql",
        "V13__pipeline_concurrency_limits.sql",
        "V14__pipeline_run_output.sql",
        "V15__pipeline_secret.sql",
        "V16__pipeline_run_rollback.sql",
        "V17__pipeline_run_job_id.sql",
        "V18__pipeline_run_principal.sql",
        "V19__pipeline_shape.sql",
        "V20__pipeline_tags.sql",
        "V21__pipeline_run_iteration_concurrency.sql",
    )
}

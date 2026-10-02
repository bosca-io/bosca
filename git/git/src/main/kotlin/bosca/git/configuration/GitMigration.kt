package bosca.git.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the `git` schema, which stores repository metadata,
 * DFS pack and ref data, pull requests, webhooks, and branch protection rules.
 */
class GitMigration : Migration {

    override val schema: String = "git"

    override val resources: List<String> = listOf(
        "V1__git_server.sql",
        "V2__branch_protection.sql",
        "V3__pull_requests.sql",
        "V4__webhooks.sql",
        "V5__lfs_and_deploy_tokens.sql",
        "V6__review_comments.sql",
        "V7__work_ops_integration.sql",
        "V8__source_refs.sql",
        "V9__drop_owner_type.sql",
        "V10__scheduled_maintenance_jobs.sql",
        "V11__scheduled_backup_job.sql",
        "V12__ci_cd_pipelines.sql",
        "V13__ci_cd_scheduled_jobs.sql",
        "V14__drop_deploy_tokens.sql",
        "V15__commit_status_state_enum.sql",
        "V16__pipeline_artifacts.sql",
        "V17__pipeline_step_definitions.sql",
        "V18__repository_content_type_analytic_query_project.sql",
        "V19__repository_content_type_agent_project.sql",
        "V20__repository_content_type_pipeline_project.sql",
        "V21__pipeline_step_error_message.sql",
        "V22__pipeline_job_error_message.sql",
        "V23__dfs_pack_soft_delete.sql",
        "V24__scheduled_pack_reap_job.sql",
        "V25__pipeline_job_artifacts.sql",
        "V26__pipeline_job_requirements.sql",
        "V27__scheduled_requirement_check_job.sql",
        "V28__pipeline_job_pipeline_requirements.sql",
        "V29__pipeline_job_attempt.sql",
        "V30__release_promotion_trigger_types.sql",
        "V31__pipeline_job_deferred_condition.sql",
        "V32__pipeline_job_environment_approval.sql",
        "V33__pipeline_run_parameters.sql",
        "V34__pipeline_secret_permissions.sql",
        "V35__pipeline_job_kubernetes_dispatch.sql",
        "V36__branch_protection_push_access_not_null.sql",
        "V37__pull_request_dependencies.sql",
        "V38__pipeline_job_requirement_bypass.sql",
        "V39__lfs_storage_path.sql",
        "V40__remove_bx_repository_content_type.sql",
        "V41__pipeline_trigger_occurrences.sql",
        "V42__repository_execute_from_edit.sql",
        "V43__pipeline_catalog_archival.sql",
        "V44__github_intake.sql",
    )
}

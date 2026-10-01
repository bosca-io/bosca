package bosca.workops.migration

import bosca.db.migrations.Migration

/**
 * Owns the Flyway migration scripts that build the `workops` Postgres schema —
 * the home of every task, project, portfolio, workflow, sprint, board, SLA,
 * automation, OKR, and audit row described in [specs/workops/requirements.md].
 *
 * Bosca's [bosca.db.migrations.FlywayMigration] discovers
 * SQL files exclusively through this `resources` list — dropping a new file
 * into `src/main/resources/db/migrations/` without registering it here causes
 * the migration runner to silently skip it. Phase ordering follows the
 * implementation plan in [specs/workops/plan.md]; later phases append.
 */
class WorkOpsMigration : Migration {

    override val schema: String = "workops"

    override val resources: List<String> = listOf(
        "V1__workops_initial.sql",
        "V2__hierarchy_and_task_core.sql",
        "V3__workflow_and_links.sql",
        "V4__sprints_boards_releases.sql",
        "V5__custom_fields_and_comments.sql",
        "V6__saved_filters.sql",
        "V7__permissions.sql",
        "V8__notifications.sql",
        "V9__sla.sql",
        "V10__automation.sql",
        "V11__worklogs.sql",
        "V12__attachments.sql",
        "V13__roadmap_okr_capacity.sql",
        "V14__cross_project.sql",
        "V15__calendar_bindings.sql",
        "V16__portal.sql",
        "V17__email_in.sql",
        "V18__ai_opt_in.sql",
        "V19__portal_forms.sql",
        "V20__federation.sql",
        "V21__entity_permissions.sql",
        "V22__project_task_form_key.sql",
        "V23__drop_portal_forms.sql",
        "V24__multi_repo_support.sql",
        "V25__specs_and_requirements.sql",
        "V26__spec_document_templates.sql",
        "V27__spec_permissions.sql",
        "V28__spec_hierarchy.sql",
        "V29__requirement_task_link.sql",
        "V30__requirement_comments.sql",
        "V31__task_metadata.sql",
        "V32__multi_project_boards.sql",
        "V33__backfill_spec_requirement_type_attribute.sql",
        "V34__set_spec_requirement_template_and_type.sql",
        "V35__release_pipeline.sql",
        "V36__program_release_pipeline.sql",
        "V37__drop_shared_component.sql",
        "V38__project_analytics.sql",
        "V39__environment_promotion_sources.sql",
        "V40__release_soft_delete.sql",
        "V41__release_name_unique_active.sql",
        "V42__project_repository.sql",
        "V43__environment_target.sql",
        "V44__environment_type.sql",
        "V45__artifact_publication_environment_scope.sql",
        "V46__release_created.sql",
        "V47__dependency_build_order_only.sql",
        "V48__drop_dependency_build_order_only.sql",
        "V49__environment_key_permissions.sql",
        "V50__environment_deployment_target.sql",
        "V51__app_build_numbers.sql",
        "V52__spec_requirement_notification_recipients.sql",
        "V53__durable_notification_delivery.sql",
        "V54__notification_event_producers.sql",
        "V55__ios_build_number_scopes.sql",
    )
}

package bosca.experimentation.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the experimentation schema, which manages feature flags,
 * A/B experiments, variant assignments, conversion goals, and analysis reports.
 */
class ExperimentationMigration : Migration {

    override val schema: String = "experimentation"

    override val resources: List<String> = listOf(
        "V1__experimentation.sql",
        "V2__goal_roles_and_policy.sql",
        "V3__bayesian_and_cuped.sql",
        "V4__assignment_identity_constraint.sql",
        "V5__conversion_goal_indexes.sql",
        "V6__explicit_control_and_engagement_goals.sql",
        "V7__feature_flag_assignments.sql",
        "V8__experiment_excluded_principals.sql",
        "V9__experiment_activation.sql",
    )
}

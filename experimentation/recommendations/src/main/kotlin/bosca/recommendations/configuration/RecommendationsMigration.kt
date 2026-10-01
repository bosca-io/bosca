package bosca.recommendations.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the recommendations schema, which manages
 * recommendation strategies, pre-computed recommendations, user dismissals,
 * display placements, and saved recommendation contexts.
 */
class RecommendationsMigration : Migration {

    override val schema: String = "recommendations"

    override val dependsOn: List<String> = listOf("segmentation")

    override val resources: List<String> = listOf(
        "V1__recommendations.sql",
        "V2__ml_model_strategy_type.sql",
        "V4__related_items.sql",
        "V5__global_candidate_pool.sql",
        "V6__co_engagement_rename.sql",
        "V7__personalization_signals.sql",
        "V8__profile_cohort.sql",
        "V9__cohort_strategy_type.sql",
        "V10__cohort_co_engagements.sql",
        "V11__recommendation_contexts.sql",
        "V12__recommendation_context_filter_shape.sql",
        "V13__profile_cohort_memberships.sql",
        "V14__recommendation_context_weights.sql",
        "V15__recommendation_context_models.sql",
        "V16__lowercase_training_status.sql",
        "V17__remove_context_model_items.sql",
    )
}

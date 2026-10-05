package bosca.recommendations.service

import bosca.analytics.model.EventType
import bosca.experimentation.model.ConversionGoalInput
import bosca.experimentation.model.ExperimentInput
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.VariationWeight
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.recommendations.model.EngineExperimentProvisioning
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Provisions the ML-vs-heuristic recommendation A/B test by orchestrating the experimentation platform's
 * flag and experiment services. All "proper values" — the variations, the 50/50 split, the goals — live
 * here so the surface (a Studio button) is a single call.
 */
@ServiceImplementation
class RecommendationExperimentServiceImpl(
    private val featureFlagService: FeatureFlagService,
    private val experimentService: ExperimentService,
) : RecommendationExperimentService {

    override suspend fun provisionEngineExperiment(): EngineExperimentProvisioning {
        val flag = featureFlagService.getByKey(FLAG_KEY) ?: featureFlagService.add(buildFlagInput())

        // Idempotent: if an experiment already exists on this flag, return it untouched rather than
        // creating duplicates each time the button is pressed.
        experimentService.getByFlagId(flag.id).firstOrNull()?.let {
            return EngineExperimentProvisioning(experimentId = it.id, flagKey = flag.key, created = false)
        }

        val experiment = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = HEURISTIC_KEY,
                name = "ML vs Heuristic Recommendations",
                description = "Measures whether the ML recommender drives more engagement than the heuristic assembler.",
                hypothesis = "The learned ranker increases content engagement and positive completions versus the heuristic assembler.",
                targetingRuleId = RULE_ID,
            )
        )
        addEngagementGoals(experiment.id)
        return EngineExperimentProvisioning(experimentId = experiment.id, flagKey = flag.key, created = true)
    }

    override suspend fun provisionModelExperiment(
        championVersion: Long,
        challengerVersion: Long,
    ): EngineExperimentProvisioning {
        val input = buildModelFlagInput(championVersion, challengerVersion)
        // Upsert the flag so re-provisioning re-points the A/B at a new version pair.
        val existing = featureFlagService.getByKey(MODEL_FLAG_KEY)
        val flag = if (existing != null) featureFlagService.edit(existing.id, input) else featureFlagService.add(input)

        experimentService.getByFlagId(flag.id).firstOrNull()?.let {
            return EngineExperimentProvisioning(experimentId = it.id, flagKey = flag.key, created = false)
        }
        val experiment = experimentService.add(
            ExperimentInput(
                featureFlagId = flag.id,
                controlVariationKey = "champion",
                name = "Recommendation Model: v$championVersion vs v$challengerVersion",
                description = "Online A/B of two recommendation model versions, measuring which drives more engagement.",
                hypothesis = "Challenger v$challengerVersion increases engagement and positive completions over champion v$championVersion.",
                targetingRuleId = MODEL_RULE_ID,
            )
        )
        addEngagementGoals(experiment.id)
        return EngineExperimentProvisioning(experimentId = experiment.id, flagKey = flag.key, created = true)
    }

    /** Discrete engagement + positive-feedback (completion) goals — shared by both A/B tests. */
    private suspend fun addEngagementGoals(experimentId: UUID) {
        experimentService.addConversionGoal(
            experimentId,
            ConversionGoalInput(name = "Engagement", eventType = EventType.Interaction, metricType = GoalMetricType.UNIQUE_CONVERSION),
        )
        experimentService.addConversionGoal(
            experimentId,
            ConversionGoalInput(name = "Positive feedback", eventType = EventType.Completion, metricType = GoalMetricType.UNIQUE_CONVERSION),
        )
    }

    /** The `recommendation-model` flag: two variations whose values are TF Serving model versions, 50/50, DISABLED. */
    private fun buildModelFlagInput(championVersion: Long, challengerVersion: Long) = FeatureFlagInput(
        key = MODEL_FLAG_KEY,
        name = "Recommendation Model",
        description = "Routes traffic between two recommendation model versions for an online A/B test.",
        type = FlagType.JSON,
        variations = buildJsonArray {
            add(buildJsonObject {
                put("key", "champion"); put("name", "Champion (v$championVersion)")
                put("description", "Current model version"); put("value", championVersion)
            })
            add(buildJsonObject {
                put("key", "challenger"); put("name", "Challenger (v$challengerVersion)")
                put("description", "Candidate model version"); put("value", challengerVersion)
            })
        },
        defaultVariationKey = "champion",
        targetingRules = Json.encodeToJsonElement(
            ListSerializer(TargetingRule.serializer()),
            listOf(
                TargetingRule(
                    id = MODEL_RULE_ID,
                    name = "50/50 champion vs challenger",
                    rollout = Rollout(listOf(VariationWeight("champion", 50), VariationWeight("challenger", 50))),
                )
            ),
        ),
        status = FlagStatus.DISABLED,
    )

    /** The `recommendation-engine` flag: two string variations split 50/50, created DISABLED (dormant). */
    private fun buildFlagInput() = FeatureFlagInput(
        key = FLAG_KEY,
        name = "Recommendation Engine",
        description = "Splits traffic between the ML ranker and the heuristic assembler to measure ML lift.",
        type = FlagType.STRING,
        variations = buildJsonArray {
            add(buildJsonObject {
                put("key", "ml"); put("name", "ML ranker"); put("description", "Two-stage learned ranker"); put("value", "ml")
            })
            add(buildJsonObject {
                put("key", HEURISTIC_KEY); put("name", "Heuristic"); put("description", "Heuristic assembler"); put("value", HEURISTIC_KEY)
            })
        },
        defaultVariationKey = "ml",
        targetingRules = Json.encodeToJsonElement(
            ListSerializer(TargetingRule.serializer()),
            listOf(
                TargetingRule(
                    id = RULE_ID,
                    name = "50/50 ML vs heuristic",
                    rollout = Rollout(listOf(VariationWeight("ml", 50), VariationWeight(HEURISTIC_KEY, 50))),
                )
            ),
        ),
        status = FlagStatus.DISABLED,
    )

    companion object {
        private const val FLAG_KEY = "recommendation-engine"
        private const val HEURISTIC_KEY = "heuristic"
        private const val RULE_ID = "recommendation-engine-5050"
        private const val MODEL_FLAG_KEY = "recommendation-model"
        private const val MODEL_RULE_ID = "recommendation-model-5050"
    }
}

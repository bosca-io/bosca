package bosca.experimentation.graphql

import bosca.analytics.model.EventType
import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.CupedCovariate
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyAction
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.RolloutStep
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolvers for [RolloutPolicy]. Delegates every field to the
 * already-deserialized data class — the policy blob is decoded once by
 * [ExperimentTypeController.rolloutPolicy] before it reaches these
 * controllers, so there is no per-field decoding cost.
 */
@TypeController(type = "RolloutPolicy")
class RolloutPolicyTypeController : GraphQLController<RolloutPolicy> {
    @Field fun mode(p: RolloutPolicy): RolloutPolicyMode = p.mode
    @Field fun treatmentVariationKey(p: RolloutPolicy): String = p.treatmentVariationKey
    @Field fun steps(p: RolloutPolicy): List<RolloutStep>? = p.steps
    @Field fun incrementPercent(p: RolloutPolicy): Double? = p.incrementPercent
    @Field fun minConfidence(p: RolloutPolicy): Double = p.minConfidence
    @Field fun guardrailThreshold(p: RolloutPolicy): Double = p.guardrailThreshold
    @Field fun guardrailMinRegressionPercent(p: RolloutPolicy): Double = p.guardrailMinRegressionPercent
    @Field fun haltOnGuardrail(p: RolloutPolicy): Boolean = p.haltOnGuardrail
}

@TypeController(type = "RolloutStep")
class RolloutStepTypeController : GraphQLController<RolloutStep> {
    @Field fun weightPercent(s: RolloutStep): Int = s.weightPercent
    @Field fun afterDuration(s: RolloutStep): String? = s.afterDuration
}

@TypeController(type = "CupedCovariate")
class CupedCovariateTypeController : GraphQLController<CupedCovariate> {
    @Field fun eventType(c: CupedCovariate): GraphQLEventType? =
        c.eventType?.let { GraphQLEventType.fromEventType(it) }
    @Field fun elementType(c: CupedCovariate): String? = c.elementType
    @Field fun elementId(c: CupedCovariate): String? = c.elementId
    @Field fun pagePath(c: CupedCovariate): String? = c.pagePath
    @Field fun lookbackWindow(c: CupedCovariate): String = c.lookbackWindow
}

@TypeController(type = "BayesianPrior")
class BayesianPriorTypeController : GraphQLController<BayesianPrior> {
    @Field fun betaPriorAlpha(p: BayesianPrior): Double = p.betaPriorAlpha
    @Field fun betaPriorBeta(p: BayesianPrior): Double = p.betaPriorBeta
    @Field fun normalPriorMean(p: BayesianPrior): Double? = p.normalPriorMean
    @Field fun normalPriorVariance(p: BayesianPrior): Double? = p.normalPriorVariance
}

@TypeController(type = "RolloutPolicyEvent")
class RolloutPolicyEventTypeController : GraphQLController<RolloutPolicyEvent> {
    @Field fun id(e: RolloutPolicyEvent): UUID = e.id
    @Field fun experimentId(e: RolloutPolicyEvent): UUID = e.experimentId
    @Field fun action(e: RolloutPolicyEvent): RolloutPolicyAction = e.action
    @Field fun reason(e: RolloutPolicyEvent): String = e.reason
    @Field fun oldWeights(e: RolloutPolicyEvent): JsonElement = e.oldWeights
    @Field fun newWeights(e: RolloutPolicyEvent): JsonElement = e.newWeights
    @Field fun created(e: RolloutPolicyEvent): OffsetDateTime = e.created
}

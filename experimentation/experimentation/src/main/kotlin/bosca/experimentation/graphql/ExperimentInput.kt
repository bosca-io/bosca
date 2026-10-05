package bosca.experimentation.graphql

import bosca.experimentation.model.AnalysisMethod
import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.RolloutPolicy
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** GraphQL-facing activation input using the schema's PascalCase event enum. */
@Serializable
data class ExperimentActivationFilterInput(
    val eventType: GraphQLEventType? = null,
    val elementType: String? = null,
    val elementId: String? = null,
    val pagePath: String? = null,
    val pagePathPrefixes: List<String>? = null,
    val itemExtraKey: String? = null,
    val itemExtraValue: String? = null,
) {
    fun toModel(): ExperimentActivationFilter = ExperimentActivationFilter(
        eventType = eventType?.toEventType(),
        elementType = elementType,
        elementId = elementId,
        pagePath = pagePath,
        pagePathPrefixes = pagePathPrefixes.orEmpty(),
        itemExtraKey = itemExtraKey,
        itemExtraValue = itemExtraValue,
    )
}

/** GraphQL mirror of the service input, with GraphQL-safe nested event enums. */
@Serializable
data class ExperimentInput(
    @Contextual
    val featureFlagId: UUID,
    val name: String,
    val description: String? = null,
    val hypothesis: String? = null,
    val targetingRuleId: String? = null,
    val controlVariationKey: String,
    val excludedPrincipalIds: List<@Contextual UUID>? = null,
    val activationFilter: ExperimentActivationFilterInput? = null,
    @Contextual
    val exclusionLayerId: UUID? = null,
    @Contextual
    val startDate: OffsetDateTime? = null,
    @Contextual
    val endDate: OffsetDateTime? = null,
    val targetSampleSize: Long? = null,
    val rolloutPolicy: RolloutPolicy? = null,
    val analysisMethod: AnalysisMethod = AnalysisMethod.FREQUENTIST,
    val bayesianPrior: BayesianPrior? = null,
)

/** Translate the GraphQL input into the core service contract. */
fun ExperimentInput.toServiceInput(): bosca.experimentation.model.ExperimentInput =
    bosca.experimentation.model.ExperimentInput(
        featureFlagId = featureFlagId,
        name = name,
        description = description,
        hypothesis = hypothesis,
        targetingRuleId = targetingRuleId,
        controlVariationKey = controlVariationKey,
        excludedPrincipalIds = excludedPrincipalIds,
        activationFilter = activationFilter?.toModel(),
        exclusionLayerId = exclusionLayerId,
        startDate = startDate,
        endDate = endDate,
        targetSampleSize = targetSampleSize,
        rolloutPolicy = rolloutPolicy,
        analysisMethod = analysisMethod,
        bayesianPrior = bayesianPrior,
    )

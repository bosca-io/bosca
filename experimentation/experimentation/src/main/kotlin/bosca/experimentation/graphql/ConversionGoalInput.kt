package bosca.experimentation.graphql

import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.CupedCovariate
import bosca.experimentation.model.GoalMetricType
import kotlinx.serialization.Serializable

/**
 * GraphQL input mirror of [CupedCovariate] — the `eventType` field
 * uses [GraphQLEventType] for the PascalCase schema enum and is
 * mapped back to the wire-format analytics EventType at the
 * service boundary.
 */
@Serializable
data class CupedCovariateInput(
    val eventType: GraphQLEventType? = null,
    val elementType: String? = null,
    val elementId: String? = null,
    val pagePath: String? = null,
    val lookbackWindow: String = CupedCovariate.DEFAULT_LOOKBACK_WINDOW,
)

fun CupedCovariateInput.toModel(): CupedCovariate = CupedCovariate(
    eventType = eventType?.toEventType(),
    elementType = elementType,
    elementId = elementId,
    pagePath = pagePath,
    lookbackWindow = lookbackWindow,
)

/**
 * GraphQL-facing input type for `addConversionGoal`.
 *
 * Mirrors the service-layer [ConversionGoalInput] structurally but uses
 * [GraphQLEventType] for the `eventType` field so the KSP-generated
 * dispatcher's `decodeFromJsonElement` call can decode the PascalCase
 * value graphql-java forwards from the schema. The mutation controller
 * translates this into the underlying [ConversionGoalInput] (which uses
 * the wire-format [bosca.analytics.model.EventType]) before handing it
 * to the experimentation service.
 *
 * Keeping the GraphQL input separate from the service input means the
 * wire-format enum stays strictly lowercase in every other code path
 * that touches it.
 */
@Serializable
data class ConversionGoalInput(
    val name: String,
    val eventType: GraphQLEventType? = null,
    val elementType: String? = null,
    val elementId: String? = null,
    val metricType: GoalMetricType = GoalMetricType.UNIQUE_CONVERSION,
    val pagePath: String? = null,
    val pagePathPrefixes: List<String>? = null,
    val itemExtraKey: String? = null,
    val itemExtraValue: String? = null,
    val role: ConversionGoalRole = ConversionGoalRole.PRIMARY,
    val cupedCovariate: CupedCovariateInput? = null,
)

/** Translate to the service-layer input. */
fun ConversionGoalInput.toServiceInput(): bosca.experimentation.model.ConversionGoalInput =
    bosca.experimentation.model.ConversionGoalInput(
        name = name,
        eventType = eventType?.toEventType(),
        elementType = elementType,
        elementId = elementId,
        metricType = metricType,
        pagePath = pagePath,
        pagePathPrefixes = pagePathPrefixes.orEmpty(),
        itemExtraKey = itemExtraKey,
        itemExtraValue = itemExtraValue,
        role = role,
        cupedCovariate = cupedCovariate?.toModel(),
    )

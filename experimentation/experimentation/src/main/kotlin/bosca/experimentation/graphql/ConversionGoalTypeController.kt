package bosca.experimentation.graphql

import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.CupedCovariate
import bosca.experimentation.model.GoalMetricType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.Json

/**
 * Resolves fields on the ConversionGoal GraphQL type. The framework uses
 * explicit `@Field` resolvers per type rather than reflective autobinding,
 * so every property exposed in the schema needs an entry here.
 */
@TypeController(type = "ConversionGoal")
class ConversionGoalTypeController(
    private val json: Json,
) : GraphQLController<ConversionGoal> {

    @Field
    fun id(goal: ConversionGoal): UUID = goal.id

    @Field
    fun experimentId(goal: ConversionGoal): UUID = goal.experimentId

    @Field
    fun name(goal: ConversionGoal): String = goal.name

    /**
     * Map the wire-format [bosca.analytics.model.EventType] held by the
     * conversion goal model to the PascalCase GraphQL-facing
     * [GraphQLEventType] before sending the response. This is the only
     * place the two enums meet on the read side; everything downstream of
     * this resolver sees the GraphQL enum.
     */
    @Field
    fun eventType(goal: ConversionGoal): GraphQLEventType? =
        goal.eventType?.let { GraphQLEventType.fromEventType(it) }

    @Field
    fun elementType(goal: ConversionGoal): String? = goal.elementType

    @Field
    fun elementId(goal: ConversionGoal): String? = goal.elementId

    @Field
    fun metricType(goal: ConversionGoal): GoalMetricType = goal.metricType

    @Field
    fun pagePath(goal: ConversionGoal): String? = goal.pagePath

    @Field
    fun pagePathPrefixes(goal: ConversionGoal): List<String> = goal.pagePathPrefixes

    @Field
    fun itemExtraKey(goal: ConversionGoal): String? = goal.itemExtraKey

    @Field
    fun itemExtraValue(goal: ConversionGoal): String? = goal.itemExtraValue

    @Field
    fun role(goal: ConversionGoal): ConversionGoalRole = goal.role

    /**
     * Decodes the stored [CupedCovariate] blob. Throws on a
     * malformed blob rather than silently resolving to null —
     * a stored configuration that can't be parsed is a bug that
     * needs to surface, not be swallowed, so an operator can
     * see and fix it.
     */
    @Field
    fun cupedCovariate(goal: ConversionGoal): CupedCovariate? {
        val element = goal.cupedCovariate ?: return null
        return json.decodeFromJsonElement(CupedCovariate.serializer(), element)
    }

    @Field
    fun created(goal: ConversionGoal): OffsetDateTime = goal.created
}

package bosca.experimentation.graphql

import bosca.experimentation.model.FlagEvaluation
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the FlagEvaluation GraphQL type. The framework uses
 * explicit `@Field` resolvers per type rather than reflective autobinding,
 * so every property exposed in the schema needs an entry here.
 */
@TypeController(type = "FlagEvaluation")
class FlagEvaluationTypeController : GraphQLController<FlagEvaluation> {

    @Field
    fun flagKey(evaluation: FlagEvaluation): String = evaluation.flagKey

    @Field
    fun variationKey(evaluation: FlagEvaluation): String = evaluation.variationKey

    @Field
    fun value(evaluation: FlagEvaluation): JsonElement = evaluation.value

    @Field
    fun experimentId(evaluation: FlagEvaluation): UUID? = evaluation.experimentId

    @Field
    fun degraded(evaluation: FlagEvaluation): Boolean = evaluation.degraded
}

package bosca.experimentation.graphql

import bosca.experimentation.model.VariationAssignmentCount
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves fields on the current feature-flag assignment count type. */
@TypeController(type = "VariationAssignmentCount")
class VariationAssignmentCountTypeController : GraphQLController<VariationAssignmentCount> {

    @Field
    fun variationKey(count: VariationAssignmentCount): String = count.variationKey

    @Field
    fun assignmentCount(count: VariationAssignmentCount): Long = count.assignmentCount
}

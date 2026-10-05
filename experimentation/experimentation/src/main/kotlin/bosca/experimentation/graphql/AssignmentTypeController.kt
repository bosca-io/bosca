package bosca.experimentation.graphql

import bosca.experimentation.model.Assignment
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves fields on the Assignment GraphQL type. Explicit `@Field` resolvers
 * are required because the framework does not autobind type fields from
 * data class properties.
 */
@TypeController(type = "Assignment")
class AssignmentTypeController : GraphQLController<Assignment> {

    @Field
    fun id(assignment: Assignment): UUID = assignment.id

    @Field
    fun experimentId(assignment: Assignment): UUID = assignment.experimentId

    @Field
    fun variationKey(assignment: Assignment): String = assignment.variationKey

    @Field
    fun principalId(assignment: Assignment): UUID? = assignment.principalId

    @Field
    fun installationId(assignment: Assignment): String? = assignment.installationId

    @Field
    fun assignedAt(assignment: Assignment): OffsetDateTime = assignment.assignedAt
}

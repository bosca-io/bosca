package bosca.analytics.graphql

import bosca.analytics.model.ErrorGroupConnection
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Field resolver for the GraphQL `ErrorGroupConnection` paged result
 * type. Pure projection of the underlying data class.
 */
@TypeController
class ErrorGroupConnectionController : GraphQLController<ErrorGroupConnection> {

    @Field
    fun edges(connection: ErrorGroupConnection) = connection.edges

    @Field
    fun total(connection: ErrorGroupConnection) = connection.total
}

package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamApiStats

/**
 * Resolves fields on the JetStreamApiStats GraphQL type, exposing
 * the total number of JetStream API calls and errors.
 */
@TypeController
class JetStreamApiStatsController : GraphQLController<JetStreamApiStats> {

    @Field
    fun total(stats: JetStreamApiStats) = stats.total

    @Field
    fun errors(stats: JetStreamApiStats) = stats.errors
}

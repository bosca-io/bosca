package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsRoute
import bosca.nats.admin.model.NatsRoutes

/**
 * Resolves fields on the NatsRoutes GraphQL type, the wrapper
 * for cluster route lists returned by the NATS `/routez` endpoint.
 */
@TypeController
class NatsRoutesController : GraphQLController<NatsRoutes> {

    @Field
    fun numRoutes(routes: NatsRoutes) = routes.numRoutes

    @Field
    fun routes(routes: NatsRoutes): List<NatsRoute> = routes.routes
}

package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsRoute

/**
 * Resolves fields on the NatsRoute GraphQL type, mapping individual
 * route connection details from the NATS `/routez` response.
 */
@TypeController
class NatsRouteController : GraphQLController<NatsRoute> {

    @Field
    fun rid(route: NatsRoute) = route.rid

    @Field
    fun remoteId(route: NatsRoute) = route.remoteId

    @Field
    fun remoteName(route: NatsRoute) = route.remoteName

    @Field
    fun didSolicit(route: NatsRoute) = route.didSolicit

    @Field
    fun isConfigured(route: NatsRoute) = route.isConfigured

    @Field
    fun ip(route: NatsRoute) = route.ip

    @Field
    fun port(route: NatsRoute) = route.port

    @Field
    fun pendingSize(route: NatsRoute) = route.pendingSize

    @Field
    fun inMsgs(route: NatsRoute) = route.inMsgs

    @Field
    fun outMsgs(route: NatsRoute) = route.outMsgs

    @Field
    fun inBytes(route: NatsRoute) = route.inBytes

    @Field
    fun outBytes(route: NatsRoute) = route.outBytes

    @Field
    fun subscriptions(route: NatsRoute) = route.subscriptions
}

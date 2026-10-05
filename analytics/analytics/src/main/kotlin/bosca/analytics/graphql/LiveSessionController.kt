package bosca.analytics.graphql

import bosca.analytics.model.LiveSession
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Field resolver for the GraphQL `LiveSession` type. The model lives in `analytics-models`; this
 * controller maps its properties to the GraphQL fields — `lat`/`lon` alias the model's
 * `latitude`/`longitude`, matching the shape the live-sessions map consumes — and is side-effect free.
 */
@TypeController
class LiveSessionController : GraphQLController<LiveSession> {

    @Field
    fun sessionId(session: LiveSession) = session.sessionId

    @Field
    fun lat(session: LiveSession) = session.latitude

    @Field
    fun lon(session: LiveSession) = session.longitude

    @Field
    fun appId(session: LiveSession) = session.appId

    @Field
    fun appVersion(session: LiveSession) = session.appVersion
}

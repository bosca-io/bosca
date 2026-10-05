package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.LogLine

/**
 * Field-level projection for the GraphQL `LogLine` type. Bosca's
 * graphql wiring uses an explicit-only field registry — properties on
 * types returned from resolvers are not auto-exposed; without this
 * controller, every field reads back as null and graphql-java's
 * non-null validator bubbles an error.
 *
 * Authorization is enforced one level up at the subscription resolver
 * in [KubernetesSubscriptionsController.k8sPodLogs].
 */
@TypeController(type = "LogLine")
class LogLineTypeController : GraphQLController<LogLine> {
    @Field fun pod(l: LogLine): String = l.pod
    @Field fun container(l: LogLine): String = l.container
    @Field fun timestamp(l: LogLine): String = l.timestamp
    @Field fun level(l: LogLine): EventLevel = l.level
    @Field fun message(l: LogLine): String = l.message
}

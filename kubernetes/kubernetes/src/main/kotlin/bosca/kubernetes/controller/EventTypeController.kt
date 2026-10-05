package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.K8sEvent
import bosca.serialization.OffsetDateTime

/**
 * Field-level projection for the GraphQL `Event` type. The `when`
 * GraphQL field is exposed via the `name` override on @Field because
 * `when` is a reserved Kotlin keyword and the KSP dispatcher generator
 * does not emit backticks for the call site.
 */
@TypeController(type = "Event")
class EventTypeController : GraphQLController<K8sEvent> {
    @Field fun id(e: K8sEvent): String = e.id
    @Field fun level(e: K8sEvent): EventLevel = e.level
    @Field(name = "when") fun whenLabel(e: K8sEvent): String = e.`when`
    @Field fun timestamp(e: K8sEvent): OffsetDateTime = e.timestamp
    @Field fun namespace(e: K8sEvent): String = e.namespace
    @Field fun involvedObject(e: K8sEvent): String = e.involvedObject
    @Field fun message(e: K8sEvent): String = e.message
    @Field fun reason(e: K8sEvent): String = e.reason
}

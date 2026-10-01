package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.ResourceChangeEvent

/**
 * Field projection for the `k8sResourcesWatch` subscription payload.
 * Bosca's graphql wiring uses an explicit-only field registry, so
 * every streamed type needs its own projection (see
 * [MetricsTypeControllers] for the rationale). Authorization is
 * enforced one level up at the subscription resolver.
 */
@TypeController(type = "ResourceChangeEvent")
class ResourceChangeEventTypeController : GraphQLController<ResourceChangeEvent> {
    @Field fun kind(e: ResourceChangeEvent): String = e.kind
    @Field fun namespace(e: ResourceChangeEvent): String? = e.namespace
    @Field fun name(e: ResourceChangeEvent): String = e.name
    @Field fun action(e: ResourceChangeEvent): String = e.action
    @Field fun timestamp(e: ResourceChangeEvent): String = e.timestamp
}

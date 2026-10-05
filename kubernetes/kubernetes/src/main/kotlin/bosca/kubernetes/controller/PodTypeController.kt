package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.Pod
import bosca.kubernetes.model.Workload

/**
 * Field-level projection for the GraphQL `Pod` type. The owning
 * `workload` field is left null until the controller exposes a
 * workload lookup keyed on `workloadId`; the studio's PodDrawer
 * gracefully handles the missing field.
 *
 * Authorization is enforced one level up at [KubernetesQueriesController].
 */
@TypeController(type = "Pod")
class PodTypeController : GraphQLController<Pod> {
    @Field fun id(p: Pod): String = p.id
    @Field fun name(p: Pod): String = p.name
    @Field fun namespace(p: Pod): String = p.namespace
    @Field fun node(p: Pod): String = p.node
    @Field fun status(p: Pod): String = p.status
    @Field fun ready(p: Pod): String = p.ready
    @Field fun restarts(p: Pod): Int = p.restarts
    @Field fun age(p: Pod): String = p.age
    @Field fun cpu(p: Pod): Int = p.cpu
    @Field fun memory(p: Pod): Int = p.memory
    @Field fun podIP(p: Pod): String? = p.podIP
    @Field fun hostIP(p: Pod): String? = p.hostIP
    @Field fun image(p: Pod): String = p.image
    @Field fun workloadKind(p: Pod): String? = p.workloadKind
    @Field fun workloadName(p: Pod): String? = p.workloadName
    @Field fun workload(@Suppress("UNUSED_PARAMETER") p: Pod): Workload? = null
}

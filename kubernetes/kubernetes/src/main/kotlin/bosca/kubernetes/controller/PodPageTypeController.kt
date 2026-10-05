package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.Pod
import bosca.kubernetes.model.PodsResponse

/**
 * Field-level projection for the GraphQL `PodPage` pagination wrapper.
 * Total/items are sourced from the controller-side filtered slice so
 * the client can render `Showing N of M`.
 */
@TypeController(type = "PodPage")
class PodPageTypeController : GraphQLController<PodsResponse> {
    @Field fun total(p: PodsResponse): Int = p.total
    @Field fun items(p: PodsResponse): List<Pod> = p.items
}

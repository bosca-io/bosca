package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.K8sNode
import kotlinx.serialization.json.JsonElement

/**
 * Field-level projection for the GraphQL `Node` type. Authorization is
 * enforced one level up at [KubernetesQueriesController].
 */
@TypeController(type = "Node")
class NodeTypeController : GraphQLController<K8sNode> {
    @Field fun name(n: K8sNode): String = n.name
    @Field fun role(n: K8sNode): String = n.role
    @Field fun instance(n: K8sNode): String = n.instance
    @Field fun zone(n: K8sNode): String = n.zone
    @Field fun status(n: K8sNode): String = n.status
    @Field fun cpu(n: K8sNode): Int = n.cpu
    @Field fun memory(n: K8sNode): Int = n.memory
    @Field fun pods(n: K8sNode): Int = n.pods
    @Field fun age(n: K8sNode): String = n.age
    @Field fun version(n: K8sNode): String = n.version
    @Field fun taints(n: K8sNode): List<String> = n.taints
    @Field fun labels(n: K8sNode): JsonElement? = n.labels
}

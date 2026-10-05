package bosca.kubernetes.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A node in a registered cluster, as surfaced by `kubernetes-controller`.
 * Shape mirrors the GraphQL `Node` type. CPU/memory percentages are
 * computed controller-side from `metrics.k8s.io` snapshots; nodes from
 * clusters without metrics installed report `0`.
 */
@Serializable
data class K8sNode(
    val name: String,
    val role: String,
    val instance: String,
    val zone: String,
    val status: String,
    val cpu: Int,
    val memory: Int,
    val pods: Int,
    val age: String,
    val version: String,
    val taints: List<String> = emptyList(),
    val labels: JsonElement? = null,
)

/** Wire envelope for `GET /clusters/{id}/nodes`. */
@Serializable
data class NodesResponse(val items: List<K8sNode>)

package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/** GatewayClass — the controller that owns Gateways. Cluster-scoped. */
@Serializable
data class K8sGatewayClass(
    val name: String,
    val controller: String,
    val accepted: Boolean,
    val age: String,
)

@Serializable
data class GatewayClassesResponse(val items: List<K8sGatewayClass>)

/**
 * Gateway API Gateway. `addresses` is the list of programmed listener
 * IPs/hostnames from `status.addresses`. `routes` is set by the
 * route at fetch time (HTTPRoute count attaching to this gateway).
 */
@Serializable
data class K8sGateway(
    val id: String,
    val name: String,
    val namespace: String,
    val gatewayClass: String,
    val addresses: List<String>,
    val listeners: Int,
    val routes: Int,
    val status: WorkloadStatus,
    val age: String,
)

@Serializable
data class GatewaysResponse(val items: List<K8sGateway>)

/**
 * Gateway API HTTPRoute. `parents` is the list of parent gateway
 * names this route attaches to (from `spec.parentRefs[].name`).
 * `backends` is `service:port` strings flattened from every rule's
 * `backendRefs`. `rules` is the count of matcher rules.
 */
@Serializable
data class K8sHttpRoute(
    val id: String,
    val name: String,
    val namespace: String,
    val parents: List<String>,
    val hosts: List<String>,
    val paths: List<String>,
    val backends: List<String>,
    val rules: Int,
    val age: String,
    val status: WorkloadStatus,
)

@Serializable
data class HttpRoutesResponse(val items: List<K8sHttpRoute>)

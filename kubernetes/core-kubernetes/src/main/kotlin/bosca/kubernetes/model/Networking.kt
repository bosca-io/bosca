package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * A kubernetes Service as surfaced by `kubernetes-controller`. Shape
 * mirrors the GraphQL `Service` type. `endpoints` is the count of
 * targeted endpoint slices (from the informer cache); we never list
 * the slices themselves on this surface.
 */
@Serializable
data class K8sService(
    val id: String,
    val name: String,
    val namespace: String,
    val type: String,
    val clusterIP: String,
    val externalIP: String,
    val ports: List<String>,
    val selector: String,
    val age: String,
    val endpoints: Int,
)

/** Wire envelope for `GET /clusters/{id}/services`. */
@Serializable
data class ServicesResponse(val items: List<K8sService>)

/**
 * A networking.k8s.io/v1 Ingress mapped to its wire shape. Ingress
 * class is sourced from `spec.ingressClassName` first, falling back to
 * the legacy `kubernetes.io/ingress.class` annotation.
 */
@Serializable
data class K8sIngress(
    val id: String,
    val name: String,
    val namespace: String,
    val ingressClass: String,
    val hosts: List<String>,
    val paths: List<String>,
    val backends: List<String>,
    val tls: Boolean,
    val age: String,
)

/** Wire envelope for `GET /clusters/{id}/ingresses`. */
@Serializable
data class IngressesResponse(val items: List<K8sIngress>)

/**
 * A NetworkPolicy. The ingress/egress fields are *summary strings* so
 * the studio's table renders a single column per direction; for the
 * detail view we'll add a separate endpoint that returns the parsed
 * rule structure.
 */
@Serializable
data class K8sNetworkPolicy(
    val id: String,
    val name: String,
    val namespace: String,
    val podSelector: String,
    val ingress: String,
    val egress: String,
    val age: String,
)

/** Wire envelope for `GET /clusters/{id}/networkpolicies`. */
@Serializable
data class NetworkPoliciesResponse(val items: List<K8sNetworkPolicy>)

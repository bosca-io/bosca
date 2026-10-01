package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * A namespace in a registered Kubernetes cluster, as surfaced by
 * `kubernetes-controller` and consumed both by the bosca-server-side
 * kubernetes module's GraphQL resolver and by the studio's namespace
 * filter.
 *
 * The shape mirrors the GraphQL `Namespace` type. Counts are sourced
 * from informer caches in `kubernetes-controller` and are best-effort —
 * a cluster that's still being walked may report partial counts.
 */
@Serializable
data class Namespace(
    val name: String,
    val status: String,
    val workloads: Int = 0,
    val pods: Int = 0,
    val services: Int = 0,
    val age: String,
)

/**
 * Wire shape for `kubernetes-controller`'s namespace listing endpoint.
 * Single-element wrapper keeps room to add cursor / paging fields
 * without changing the route URL.
 */
@Serializable
data class NamespacesResponse(
    val items: List<Namespace>,
)

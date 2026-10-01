package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * An installed CRD-providing operator, surfaced as a single row per
 * API group. Mirrors the GraphQL `Operator` type.
 *
 *  * `instances` is the total CR instance count across every kind in
 *    the group.
 *  * `kinds` is the deduplicated set of `spec.names.kind` across the
 *    group's CRDs.
 *  * `status` is OK if every CRD in the group is `Established`, ERROR
 *    if any is not, WARN if mixed.
 *  * `namespace` is the operator controller's namespace when we can
 *    derive it from a CRD annotation, otherwise empty.
 *  * `version` is the highest served CRD version in the group.
 */
@Serializable
data class K8sOperator(
    val id: String,
    val name: String,
    val version: String,
    val group: String,
    val namespace: String,
    val status: WorkloadStatus,
    val instances: Int,
    val kinds: List<String>,
    val description: String,
)

/** Wire envelope for `GET /clusters/{id}/operators`. */
@Serializable
data class OperatorsResponse(val items: List<K8sOperator>)

/**
 * An instance of any CRD-defined kind. `detail` is a controller-side
 * one-liner derived from the most informative status field we can
 * find — `status.phase`, then the first Ready/Available condition's
 * message, then empty.
 */
@Serializable
data class K8sCustomResource(
    val id: String,
    val kind: String,
    val group: String,
    val version: String,
    val namespace: String,
    val name: String,
    val age: String,
    val status: WorkloadStatus,
    val detail: String,
)

/** Wire envelope for `GET /clusters/{id}/customresources`. */
@Serializable
data class CustomResourcesResponse(val items: List<K8sCustomResource>)

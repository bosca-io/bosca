package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * Unified Role view spanning Role + ClusterRole — the studio's
 * Access Control page renders both kinds in one list with the `kind`
 * column distinguishing them.
 *
 *  * `builtin` is true for Kubernetes-managed roles: anything in the
 *    `system:` prefix plus the well-known
 *    `{cluster-admin, view, edit, admin}` cluster-roles.
 *  * `bindings` is 0 for now — counting requires cross-scanning every
 *    binding kind; a follow-up wires it from the binding informer.
 */
@Serializable
data class K8sRole(
    val id: String,
    val kind: String,
    val name: String,
    val builtin: Boolean,
    val bindings: Int,
    val rules: Int,
    val age: String,
    val namespace: String? = null,
    val description: String = "",
)

/** Wire envelope for `GET /clusters/{id}/roles`. */
@Serializable
data class RolesResponse(val items: List<K8sRole>)

@Serializable
data class K8sRoleSubject(
    val kind: String,
    val name: String,
    val namespace: String? = null,
)

/** Unified binding view spanning RoleBinding + ClusterRoleBinding. */
@Serializable
data class K8sRoleBinding(
    val id: String,
    val kind: String,
    val name: String,
    val role: String,
    val subjects: List<K8sRoleSubject>,
    val namespace: String? = null,
    val age: String,
)

/** Wire envelope for `GET /clusters/{id}/rolebindings`. */
@Serializable
data class RoleBindingsResponse(val items: List<K8sRoleBinding>)

/**
 * A ServiceAccount. `iamRole` is sourced from the standard cloud-
 * provider annotations:
 *   * `eks.amazonaws.com/role-arn` (AWS IRSA)
 *   * `iam.gke.io/gcp-service-account` (GKE Workload Identity)
 *   * `azure.workload.identity/client-id` (AKS Workload Identity)
 */
@Serializable
data class K8sServiceAccount(
    val id: String,
    val name: String,
    val namespace: String,
    val pods: Int,
    val secrets: Int,
    val bindings: Int,
    val age: String,
    val iamRole: String? = null,
)

/** Wire envelope for `GET /clusters/{id}/serviceaccounts`. */
@Serializable
data class ServiceAccountsResponse(val items: List<K8sServiceAccount>)

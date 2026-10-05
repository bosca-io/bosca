package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sRole
import bosca.kubernetes.model.K8sRoleBinding
import bosca.kubernetes.model.K8sRoleSubject
import bosca.kubernetes.model.K8sServiceAccount
import io.fabric8.kubernetes.api.model.ServiceAccount
import io.fabric8.kubernetes.api.model.rbac.ClusterRole
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding
import io.fabric8.kubernetes.api.model.rbac.Role
import io.fabric8.kubernetes.api.model.rbac.RoleBinding

private val BUILT_IN_ROLES = setOf("cluster-admin", "view", "edit", "admin")

fun Role.toWire(bindingCount: Int = 0): K8sRole = K8sRole(
    id = metadata?.uid ?: "Role/${metadata?.namespace}/${metadata?.name}",
    kind = "Role",
    name = metadata?.name.orEmpty(),
    builtin = isBuiltInRoleName(metadata?.name),
    bindings = bindingCount,
    rules = rules?.size ?: 0,
    age = formatAge(metadata?.creationTimestamp),
    namespace = metadata?.namespace,
    description = metadata?.annotations?.get("rbac.authorization.kubernetes.io/autoupdate")
        ?.let { "autoupdate=$it" }
        ?: metadata?.annotations?.get("description")
        ?: "",
)

fun ClusterRole.toWire(bindingCount: Int = 0): K8sRole = K8sRole(
    id = metadata?.uid ?: "ClusterRole/${metadata?.name}",
    kind = "ClusterRole",
    name = metadata?.name.orEmpty(),
    builtin = isBuiltInRoleName(metadata?.name),
    bindings = bindingCount,
    rules = rules?.size ?: 0,
    age = formatAge(metadata?.creationTimestamp),
    namespace = null,
    description = metadata?.annotations?.get("rbac.authorization.kubernetes.io/autoupdate")
        ?.let { "autoupdate=$it" }
        ?: metadata?.annotations?.get("description")
        ?: "",
)

/**
 * "Built-in" detection: anything kubernetes ships with — by name. The
 * `system:` prefix covers cluster-scoped service accounts'
 * permissions; the named four (`cluster-admin`, `view`, `edit`,
 * `admin`) are the user-facing baseline roles that ship with every
 * cluster.
 */
private fun isBuiltInRoleName(name: String?): Boolean {
    val n = name ?: return false
    return n.startsWith("system:") || n in BUILT_IN_ROLES
}

fun RoleBinding.toWire(): K8sRoleBinding = K8sRoleBinding(
    id = metadata?.uid ?: "RoleBinding/${metadata?.namespace}/${metadata?.name}",
    kind = "RoleBinding",
    name = metadata?.name.orEmpty(),
    role = roleRef?.name.orEmpty(),
    subjects = subjects.orEmpty().map { s ->
        K8sRoleSubject(kind = s.kind.orEmpty(), name = s.name.orEmpty(), namespace = s.namespace)
    },
    namespace = metadata?.namespace,
    age = formatAge(metadata?.creationTimestamp),
)

fun ClusterRoleBinding.toWire(): K8sRoleBinding = K8sRoleBinding(
    id = metadata?.uid ?: "ClusterRoleBinding/${metadata?.name}",
    kind = "ClusterRoleBinding",
    name = metadata?.name.orEmpty(),
    role = roleRef?.name.orEmpty(),
    subjects = subjects.orEmpty().map { s ->
        K8sRoleSubject(kind = s.kind.orEmpty(), name = s.name.orEmpty(), namespace = s.namespace)
    },
    namespace = null,
    age = formatAge(metadata?.creationTimestamp),
)

fun ServiceAccount.toWire(podCount: Int = 0, bindingCount: Int = 0): K8sServiceAccount = K8sServiceAccount(
    id = metadata?.uid ?: "ServiceAccount/${metadata?.namespace}/${metadata?.name}",
    name = metadata?.name.orEmpty(),
    namespace = metadata?.namespace.orEmpty(),
    pods = podCount,
    secrets = secrets?.size ?: 0,
    bindings = bindingCount,
    age = formatAge(metadata?.creationTimestamp),
    iamRole = iamRoleAnnotation(metadata?.annotations),
)

private fun iamRoleAnnotation(annotations: Map<String, String>?): String? {
    if (annotations.isNullOrEmpty()) return null
    return annotations["eks.amazonaws.com/role-arn"]
        ?: annotations["iam.gke.io/gcp-service-account"]
        ?: annotations["azure.workload.identity/client-id"]
}

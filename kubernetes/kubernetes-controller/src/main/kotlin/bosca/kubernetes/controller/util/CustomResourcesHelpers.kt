package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sCustomResource
import bosca.kubernetes.model.K8sOperator
import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition

/**
 * API groups we consider "kubernetes-native" and intentionally skip
 * when building the operator list — they aren't installed by an
 * operator, they're shipped with the cluster (or part of the
 * extensions API that every cluster has).
 */
private val SKIPPED_GROUPS = setOf(
    "",
    "apps",
    "batch",
    "autoscaling",
    "networking.k8s.io",
    "rbac.authorization.k8s.io",
    "storage.k8s.io",
    "policy",
    "scheduling.k8s.io",
    "coordination.k8s.io",
    "discovery.k8s.io",
    "events.k8s.io",
    "node.k8s.io",
    "flowcontrol.apiserver.k8s.io",
    "admissionregistration.k8s.io",
    "apiregistration.k8s.io",
    "authentication.k8s.io",
    "authorization.k8s.io",
    "certificates.k8s.io",
    "apiextensions.k8s.io",
)

/**
 * Groups [crds] by `.spec.group` and builds one [K8sOperator] row per
 * group. Built-in kubernetes groups are filtered out so the operators
 * page surfaces only third-party / user-installed APIs.
 *
 * `instances` and `kinds` are filled in by the route once it has
 * walked the CRD instances — this helper keeps that wiring out of
 * the mapping logic so testing the mapping doesn't require a fabric8
 * client.
 */
fun groupCrdsToOperators(
    crds: List<CustomResourceDefinition>,
    instancesByGroup: Map<String, Int>,
): List<K8sOperator> {
    val byGroup = crds.groupBy { it.spec?.group.orEmpty() }
    return byGroup.entries
        .filter { it.key.isNotBlank() && it.key !in SKIPPED_GROUPS }
        .map { (group, defs) ->
            val kinds = defs.mapNotNull { it.spec?.names?.kind }.distinct().sorted()
            val established = defs.count { it.isEstablished() }
            val status = when {
                established == defs.size -> WorkloadStatus.OK
                established == 0 -> WorkloadStatus.ERROR
                else -> WorkloadStatus.WARN
            }
            val version = pickHighestServedVersion(defs)
            val descriptor = defs.firstOrNull { it.metadata?.annotations?.containsKey("description") == true }
            K8sOperator(
                id = "Operator/$group",
                name = group,
                version = version,
                group = group,
                namespace = descriptor?.metadata?.annotations?.get("app.kubernetes.io/operator-namespace") ?: "",
                status = status,
                instances = instancesByGroup[group] ?: 0,
                kinds = kinds,
                description = descriptor?.metadata?.annotations?.get("description")
                    ?: descriptor?.metadata?.annotations?.get("operator.openshift.io/displayName")
                    ?: "",
            )
        }
        .sortedBy { it.group }
}

/**
 * Picks the "newest" served version across [defs]. Kubernetes
 * convention orders versions as `v1`, `v1beta2`, `v1beta1`,
 * `v1alpha1`, etc.; we pick by a simple alphabetical descending sort
 * (since stable > beta > alpha within a major).
 */
private fun pickHighestServedVersion(defs: List<CustomResourceDefinition>): String {
    val served = defs.flatMap { d -> d.spec?.versions.orEmpty().filter { it.served == true }.mapNotNull { it.name } }
    return served.sortedDescending().firstOrNull() ?: ""
}

private fun CustomResourceDefinition.isEstablished(): Boolean =
    status?.conditions.orEmpty().any { it.type == "Established" && it.status == "True" }

/**
 * Maps a fabric8 [GenericKubernetesResource] into our wire
 * [K8sCustomResource] shape. We infer a coarse status by scanning
 * `status.phase` then `status.conditions` for Ready/Available signals,
 * matching how `kubectl get crd-instance` would render activity.
 */
fun GenericKubernetesResource.toCustomResource(group: String, version: String): K8sCustomResource {
    val statusObj = additionalProperties["status"] as? Map<*, *>
    val phase = statusObj?.get("phase")?.toString().orEmpty()
    val readyCondition = (statusObj?.get("conditions") as? List<*>)?.asSequence()
        ?.mapNotNull { it as? Map<*, *> }
        ?.firstOrNull { (it["type"] == "Ready" || it["type"] == "Available") }

    val readyStatus = readyCondition?.get("status")?.toString()
    val readyMessage = readyCondition?.get("message")?.toString().orEmpty()

    val status = when {
        phase.equals("Failed", ignoreCase = true) || phase.equals("Error", ignoreCase = true) -> WorkloadStatus.ERROR
        readyStatus == "False" -> WorkloadStatus.ERROR
        phase.equals("Pending", ignoreCase = true) -> WorkloadStatus.WARN
        readyStatus == "Unknown" -> WorkloadStatus.WARN
        phase.isBlank() && readyStatus == null -> WorkloadStatus.OK   // controllers that don't expose a status — assume OK
        else -> WorkloadStatus.OK
    }
    val detail = when {
        phase.isNotBlank() -> phase
        readyMessage.isNotBlank() -> readyMessage
        else -> ""
    }
    return K8sCustomResource(
        id = metadata?.uid ?: "$kind/${metadata?.namespace}/${metadata?.name}",
        kind = kind.orEmpty(),
        group = group,
        version = version,
        namespace = metadata?.namespace.orEmpty(),
        name = metadata?.name.orEmpty(),
        age = formatAge(metadata?.creationTimestamp),
        status = status,
        detail = detail,
    )
}

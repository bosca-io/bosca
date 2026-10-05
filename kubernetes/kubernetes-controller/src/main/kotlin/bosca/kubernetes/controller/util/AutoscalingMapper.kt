package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sHpa
import bosca.kubernetes.model.K8sHpaMetric
import bosca.kubernetes.model.K8sPdb
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricStatus
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricTarget
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricValueStatus
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget

/**
 * Maps a fabric8 autoscaling/v2 [HorizontalPodAutoscaler] into our
 * wire shape.
 *
 *  * `minReplicas` defaults to 1 when unset — the API server's own
 *    default.
 *  * Each of the five metric source types (Resource /
 *    ContainerResource / Pods / Object / External) flattens to a
 *    `label / current / target` triple. Current values pair with
 *    their spec rule by source type + metric identity, not by index —
 *    the status array is not order-guaranteed.
 *  * Condition flags default to the neutral reading when the
 *    controller hasn't reconciled yet (no conditions on a fresh HPA).
 *  * `lastScaleTime` renders as a relative phrase (`5m ago`) to match
 *    the events timeline; null when the HPA has never scaled.
 */
fun HorizontalPodAutoscaler.toWire(): K8sHpa {
    val currentByKey = (status?.currentMetrics ?: emptyList()).associateBy { it.identityKey() }
    val metrics = (spec?.metrics ?: emptyList()).map { rule ->
        K8sHpaMetric(
            label = rule.label(),
            current = currentByKey[rule.identityKey()]?.currentValue(),
            target = rule.targetValue(),
        )
    }
    val conditions = status?.conditions ?: emptyList()
    fun condition(type: String): Boolean? =
        conditions.firstOrNull { it.type == type }?.let { it.status == "True" }
    return K8sHpa(
        id = metadata?.uid ?: "HorizontalPodAutoscaler/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        targetKind = spec?.scaleTargetRef?.kind.orEmpty(),
        targetName = spec?.scaleTargetRef?.name.orEmpty(),
        minReplicas = spec?.minReplicas ?: 1,
        maxReplicas = spec?.maxReplicas ?: 0,
        currentReplicas = status?.currentReplicas ?: 0,
        desiredReplicas = status?.desiredReplicas ?: 0,
        metrics = metrics,
        ableToScale = condition("AbleToScale") ?: true,
        scalingActive = condition("ScalingActive") ?: true,
        scalingLimited = condition("ScalingLimited") ?: false,
        lastScaleTime = status?.lastScaleTime?.let { formatRelativeTime(it) },
        age = formatAge(metadata?.creationTimestamp),
    )
}

/**
 * Maps a fabric8 policy/v1 [PodDisruptionBudget] into our wire shape.
 * `minAvailable` / `maxUnavailable` keep the API's int-or-percent
 * string form; the selector renders in `kubectl` label-selector
 * syntax (`k=v,key in (a,b)`), `-` when empty.
 */
fun PodDisruptionBudget.toWire(): K8sPdb = K8sPdb(
    id = metadata?.uid ?: "PodDisruptionBudget/${metadata?.namespace}/${metadata?.name}",
    name = metadata?.name.orEmpty(),
    namespace = metadata?.namespace.orEmpty(),
    minAvailable = spec?.minAvailable?.render(),
    maxUnavailable = spec?.maxUnavailable?.render(),
    currentHealthy = status?.currentHealthy ?: 0,
    desiredHealthy = status?.desiredHealthy ?: 0,
    disruptionsAllowed = status?.disruptionsAllowed ?: 0,
    expectedPods = status?.expectedPods ?: 0,
    selector = spec?.selector.render(),
    age = formatAge(metadata?.creationTimestamp),
)

private fun IntOrString.render(): String = strVal ?: intVal?.toString() ?: "-"

private fun LabelSelector?.render(): String {
    if (this == null) return "-"
    val labels = matchLabels.orEmpty().entries.map { "${it.key}=${it.value}" }
    val expressions = matchExpressions.orEmpty().map { expr ->
        when (expr.operator) {
            "In" -> "${expr.key} in (${expr.values.orEmpty().joinToString(",")})"
            "NotIn" -> "${expr.key} notin (${expr.values.orEmpty().joinToString(",")})"
            "Exists" -> expr.key
            "DoesNotExist" -> "!${expr.key}"
            else -> "${expr.key} ${expr.operator}"
        }
    }
    val rendered = (labels + expressions).joinToString(",")
    return rendered.ifBlank { "-" }
}

// ===== metric flattening =====================================================

/**
 * Identity for pairing a status entry with its spec rule. Uses the
 * source type plus the metric's own name (resource name, custom
 * metric name, …) — mirrors how the HPA controller itself reports
 * `currentMetrics`.
 */
private fun MetricSpec.identityKey(): String = when (type) {
    "Resource" -> "Resource/${resource?.name}"
    "ContainerResource" -> "ContainerResource/${containerResource?.name}/${containerResource?.container}"
    "Pods" -> "Pods/${pods?.metric?.name}"
    "Object" -> "Object/${`object`?.metric?.name}/${`object`?.describedObject?.kind}/${`object`?.describedObject?.name}"
    "External" -> "External/${external?.metric?.name}"
    else -> "Unknown/$type"
}

private fun MetricStatus.identityKey(): String = when (type) {
    "Resource" -> "Resource/${resource?.name}"
    "ContainerResource" -> "ContainerResource/${containerResource?.name}/${containerResource?.container}"
    "Pods" -> "Pods/${pods?.metric?.name}"
    "Object" -> "Object/${`object`?.metric?.name}/${`object`?.describedObject?.kind}/${`object`?.describedObject?.name}"
    "External" -> "External/${external?.metric?.name}"
    else -> "Unknown/$type"
}

/** Human label for the rule — `cpu`, `memory (app)`, `requests-per-second on Service/web`. */
private fun MetricSpec.label(): String = when (type) {
    "Resource" -> resource?.name ?: "resource"
    "ContainerResource" -> "${containerResource?.name} (${containerResource?.container})"
    "Pods" -> pods?.metric?.name ?: "pods"
    "Object" -> "${`object`?.metric?.name} on ${`object`?.describedObject?.kind}/${`object`?.describedObject?.name}"
    "External" -> external?.metric?.name ?: "external"
    else -> type ?: "unknown"
}

private fun MetricSpec.targetValue(): String = when (type) {
    "Resource" -> resource?.target.render()
    "ContainerResource" -> containerResource?.target.render()
    "Pods" -> pods?.target.render()
    "Object" -> `object`?.target.render()
    "External" -> external?.target.render()
    else -> "-"
}

private fun MetricStatus.currentValue(): String? = when (type) {
    "Resource" -> resource?.current.render()
    "ContainerResource" -> containerResource?.current.render()
    "Pods" -> pods?.current.render()
    "Object" -> `object`?.current.render()
    "External" -> external?.current.render()
    else -> null
}

/** `Utilization` renders as a percentage; quantity forms render the raw quantity (`100m`, `2Gi`). */
private fun MetricTarget?.render(): String = when {
    this == null -> "-"
    averageUtilization != null -> "$averageUtilization%"
    averageValue != null -> averageValue.toString()
    value != null -> value.toString()
    else -> "-"
}

private fun MetricValueStatus?.render(): String? = when {
    this == null -> null
    averageUtilization != null -> "$averageUtilization%"
    averageValue != null -> averageValue.toString()
    value != null -> value.toString()
    else -> null
}

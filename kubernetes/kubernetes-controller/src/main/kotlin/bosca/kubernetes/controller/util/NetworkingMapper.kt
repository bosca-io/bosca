package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sIngress
import bosca.kubernetes.model.K8sNetworkPolicy
import bosca.kubernetes.model.K8sService
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy

/**
 * Maps a fabric8 [Service] into our wire [K8sService] shape.
 *
 *  * `clusterIP` is `"None"` for headless services (`clusterIP=None`).
 *  * `externalIP` is the first LoadBalancer ingress (IP or hostname),
 *    falling back to `spec.externalIPs[0]`, falling back to `"-"`.
 *  * `ports` is rendered as `external→internal/proto` per port —
 *    matches `kubectl get svc -o wide` output.
 *  * `selector` is a comma-joined `k=v` string.
 *  * `endpoints` is the count of ready endpoint addresses across all
 *    EndpointSlices backing this service, threaded in by the route
 *    layer (it does a single cluster-wide EndpointSlice list and
 *    buckets by the `kubernetes.io/service-name` label).
 */
fun Service.toWire(endpointCount: Int = 0): K8sService {
    val portStrings = spec?.ports.orEmpty().map { p ->
        val ext = p.port?.toString() ?: "?"
        val tgt = renderIntOrString(p.targetPort)
        val proto = p.protocol ?: "TCP"
        if (tgt.isEmpty()) "$ext/$proto" else "$ext→$tgt/$proto"
    }
    val selector = (spec?.selector ?: emptyMap()).entries
        .joinToString(", ") { "${it.key}=${it.value}" }
    val lbIngress = status?.loadBalancer?.ingress?.firstOrNull()
    val externalIp = lbIngress?.ip
        ?: lbIngress?.hostname
        ?: spec?.externalIPs?.firstOrNull()
        ?: "-"

    return K8sService(
        id = metadata?.uid ?: "Service/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        type = spec?.type ?: "ClusterIP",
        clusterIP = spec?.clusterIP ?: "-",
        externalIP = externalIp,
        ports = portStrings,
        selector = selector,
        age = formatAge(metadata?.creationTimestamp),
        endpoints = endpointCount,
    )
}

/**
 * Maps a fabric8 [Ingress] into our wire [K8sIngress] shape. Ingress
 * class resolution: `spec.ingressClassName` wins, then the legacy
 * `kubernetes.io/ingress.class` annotation, then `"-"`.
 *
 * `backends` flattens every rule's path → backend tuple into
 * `service:port` strings (or `service-name:port` when the backend
 * names a specific port). Duplicates are not deduped — admins want to
 * see fan-out as it is.
 */
fun Ingress.toWire(): K8sIngress {
    val rules = spec?.rules.orEmpty()
    val hosts = rules.mapNotNull { it.host }.distinct()
    val paths = rules.flatMap { r ->
        r.http?.paths?.mapNotNull { p -> p.path } ?: emptyList()
    }.distinct()
    val backends = rules.flatMap { r ->
        r.http?.paths.orEmpty().mapNotNull { p ->
            val svc = p.backend?.service ?: return@mapNotNull null
            val port = svc.port?.number?.toString() ?: svc.port?.name.orEmpty()
            if (port.isBlank()) svc.name else "${svc.name}:$port"
        }
    }.distinct()
    val className = spec?.ingressClassName
        ?: metadata?.annotations?.get("kubernetes.io/ingress.class")
        ?: "-"
    return K8sIngress(
        id = metadata?.uid ?: "Ingress/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        ingressClass = className,
        hosts = hosts,
        paths = paths,
        backends = backends,
        tls = !spec?.tls.isNullOrEmpty(),
        age = formatAge(metadata?.creationTimestamp),
    )
}

/**
 * Maps a fabric8 [NetworkPolicy] into our wire [K8sNetworkPolicy]
 * shape. The `ingress` / `egress` columns are *single-line summaries*
 * because the studio's table needs to fit a row at a glance:
 *
 *   * Empty rule list with policyType present → `deny-all` for that
 *     direction.
 *   * Rule list non-empty → `N rule(s)` so the user knows there's
 *     content to drill into.
 *   * Absent direction → `-` so the column isn't blank.
 */
fun NetworkPolicy.toWire(): K8sNetworkPolicy {
    val selector = formatSelector(spec?.podSelector)
    val types = spec?.policyTypes.orEmpty()
    val ingressRules = spec?.ingress.orEmpty()
    val egressRules = spec?.egress.orEmpty()

    val ingressLabel = when {
        "Ingress" !in types && ingressRules.isEmpty() -> "-"
        ingressRules.isEmpty() -> "deny-all"
        else -> "${ingressRules.size} rule" + if (ingressRules.size == 1) "" else "s"
    }
    val egressLabel = when {
        "Egress" !in types && egressRules.isEmpty() -> "-"
        egressRules.isEmpty() -> "deny-all"
        else -> "${egressRules.size} rule" + if (egressRules.size == 1) "" else "s"
    }

    return K8sNetworkPolicy(
        id = metadata?.uid ?: "NetworkPolicy/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        podSelector = selector.ifBlank { "all pods" },
        ingress = ingressLabel,
        egress = egressLabel,
        age = formatAge(metadata?.creationTimestamp),
    )
}

// IntOrString inherits Lombok's `@ToString` from AnyType, which renders
// `AnyType(value=8080)` rather than the wrapped scalar — unhelpful for
// the UI. Unwrap to the underlying Int or String instead.
private fun renderIntOrString(value: IntOrString?): String = when (val v = value?.value) {
    is Int -> v.toString()
    is String -> v
    null -> ""
    else -> v.toString()
}

/**
 * Renders a fabric8 [LabelSelector] into a single-line comma-joined
 * string of `key=value` pairs from `matchLabels`, followed by any
 * `matchExpressions` rendered as `key OP (v1, v2, ...)`. The output
 * is intended for display, not for round-tripping to k8s.
 */
private fun formatSelector(sel: LabelSelector?): String {
    if (sel == null) return ""
    val matchLabels = sel.matchLabels.orEmpty().entries
        .joinToString(", ") { "${it.key}=${it.value}" }
    val matchExprs = sel.matchExpressions.orEmpty().joinToString(", ") { expr ->
        val values = expr.values.orEmpty().joinToString(",")
        "${expr.key} ${expr.operator} (${values})"
    }
    return listOf(matchLabels, matchExprs).filter { it.isNotBlank() }.joinToString(", ")
}

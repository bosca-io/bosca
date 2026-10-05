package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sGateway
import bosca.kubernetes.model.K8sGatewayClass
import bosca.kubernetes.model.K8sHttpRoute
import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.gatewayapi.v1.Gateway
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayClass
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute

/**
 * Maps fabric8 Gateway API typed models into our wire shape. Status
 * derivation funnels through `status.conditions` — Gateway API spec
 * uses `Accepted` and `Programmed` types; we map them to OK/WARN/ERROR
 * the same way every other status-aware row does.
 */

fun GatewayClass.toWire(): K8sGatewayClass {
    val accepted = status?.conditions.orEmpty().any { it.type == "Accepted" && it.status == "True" }
    return K8sGatewayClass(
        name = metadata?.name.orEmpty(),
        controller = spec?.controllerName.orEmpty(),
        accepted = accepted,
        age = formatAge(metadata?.creationTimestamp),
    )
}

/**
 * Maps a fabric8 [Gateway] to our wire row. `routes` is intentionally
 * left at 0 here — the route count requires a separate HTTPRoute scan
 * which the route layer performs once per cluster and stitches in.
 */
fun Gateway.toWire(routes: Int = 0): K8sGateway {
    val addresses = status?.addresses.orEmpty().mapNotNull { it.value }
    val listenerCount = spec?.listeners?.size ?: 0
    val accepted = status?.conditions.orEmpty().firstOrNull { it.type == "Accepted" }
    val programmed = status?.conditions.orEmpty().firstOrNull { it.type == "Programmed" }
    val workloadStatus = when {
        accepted?.status == "False" || programmed?.status == "False" -> WorkloadStatus.ERROR
        programmed?.status == "True" -> WorkloadStatus.OK
        accepted?.status == "True" -> WorkloadStatus.WARN
        else -> WorkloadStatus.WARN
    }
    return K8sGateway(
        id = metadata?.uid ?: "Gateway/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        gatewayClass = spec?.gatewayClassName.orEmpty(),
        addresses = addresses,
        listeners = listenerCount,
        routes = routes,
        status = workloadStatus,
        age = formatAge(metadata?.creationTimestamp),
    )
}

/**
 * Maps a fabric8 [HTTPRoute] to our wire row. Backend references are
 * flattened to `service:port` strings; backends that target a
 * non-Service kind (e.g. a third-party backend) are still listed,
 * with the kind embedded for context.
 */
fun HTTPRoute.toWire(): K8sHttpRoute {
    val parents = spec?.parentRefs.orEmpty().mapNotNull { it.name }.distinct()
    val hosts = spec?.hostnames.orEmpty()
    val rules = spec?.rules.orEmpty()
    val paths = rules.flatMap { r ->
        r.matches.orEmpty().mapNotNull { it.path?.value }
    }.distinct()
    val backends = rules.flatMap { r ->
        r.backendRefs.orEmpty().map { ref ->
            val name = ref.name.orEmpty()
            val port = ref.port?.toString().orEmpty()
            val kind = ref.kind?.takeIf { it.isNotBlank() && it != "Service" }
            val base = if (port.isBlank()) name else "$name:$port"
            if (kind != null) "$kind/$base" else base
        }
    }.distinct()

    val accepted = status?.parents.orEmpty().firstOrNull()
        ?.conditions.orEmpty().firstOrNull { it.type == "Accepted" }
    val resolved = status?.parents.orEmpty().firstOrNull()
        ?.conditions.orEmpty().firstOrNull { it.type == "ResolvedRefs" }
    val workloadStatus = when {
        accepted?.status == "False" || resolved?.status == "False" -> WorkloadStatus.ERROR
        accepted?.status == "True" && resolved?.status == "True" -> WorkloadStatus.OK
        accepted?.status == "True" || resolved?.status == "True" -> WorkloadStatus.WARN
        else -> WorkloadStatus.WARN
    }

    return K8sHttpRoute(
        id = metadata?.uid ?: "HTTPRoute/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        parents = parents,
        hosts = hosts,
        paths = paths,
        backends = backends,
        rules = rules.size,
        age = formatAge(metadata?.creationTimestamp),
        status = workloadStatus,
    )
}

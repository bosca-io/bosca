package bosca.kubernetes.controller.util

import bosca.kubernetes.controller.metrics.MetricsService
import bosca.kubernetes.model.K8sNode
import io.fabric8.kubernetes.api.model.Quantity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.fabric8.kubernetes.api.model.Node as K8sFabricNode

/**
 * Maps a fabric8 `Node` into our wire [K8sNode] shape.
 *
 * Role derivation prioritises the explicit `control-plane` label so
 * managed clusters that hide the legacy `master` role still report
 * correctly. Status is the *first signal-condition that's true* —
 * `Ready=False` surfaces as `NotReady`, otherwise we promote any of
 * the pressure conditions (`MemoryPressure`, `DiskPressure`,
 * `PIDPressure`, `NetworkUnavailable`) so the studio's NodeFleetGrid
 * highlights nodes that need attention even when they're nominally
 * Ready.
 *
 * The route layer passes [usage] (sourced from `metrics.k8s.io` via
 * [MetricsService]) and [podCount] (counted from the pod list); both
 * default to "unknown" so the mapper still works for unit tests that
 * don't have metrics or pod data to thread through.
 */
fun K8sFabricNode.toK8sNode(
    usage: MetricsService.NodeUsage? = null,
    podCount: Int = 0,
): K8sNode {
    val labels = metadata?.labels.orEmpty()
    val statusText = nodeStatusText()

    // Convert raw usage into a 0–100 utilisation percentage against
    // the node's allocatable resources. Allocatable (not capacity) is
    // the right denominator — capacity counts reserved system memory
    // and would always make Ready nodes look idle.
    val allocatable = status?.allocatable.orEmpty()
    val cpuPercent = percentOf(usage?.cpuMillicores, allocatableMillicores(allocatable["cpu"]))
    val memoryPercent = percentOf(usage?.memoryBytes, allocatableBytes(allocatable["memory"]))

    return K8sNode(
        name = metadata?.name.orEmpty(),
        role = deriveRole(labels),
        instance = labels["node.kubernetes.io/instance-type"]
            ?: labels["beta.kubernetes.io/instance-type"]
            ?: "",
        zone = labels["topology.kubernetes.io/zone"]
            ?: labels["failure-domain.beta.kubernetes.io/zone"]
            ?: "",
        status = statusText,
        cpu = cpuPercent,
        memory = memoryPercent,
        pods = podCount,
        age = formatAge(metadata?.creationTimestamp),
        version = status?.nodeInfo?.kubeletVersion.orEmpty(),
        taints = spec?.taints.orEmpty().map { t ->
            buildString {
                append(t.key.orEmpty())
                if (!t.value.isNullOrBlank()) append("=").append(t.value)
                append(":").append(t.effect.orEmpty())
            }
        },
        labels = if (labels.isEmpty()) null else labelsAsJson(labels),
    )
}

private fun allocatableMillicores(quantity: Quantity?): Long =
    if (quantity == null) 0 else MetricsService.parseCpuMillicores(quantity)

private fun allocatableBytes(quantity: Quantity?): Long =
    if (quantity == null) 0 else MetricsService.parseMemoryBytes(quantity)

private fun percentOf(used: Long?, total: Long): Int {
    if (used == null || total <= 0) return 0
    val pct = (used * 100.0 / total).toInt()
    return pct.coerceIn(0, 100)
}

/**
 * Derives the node's coarse readiness text the same way [toK8sNode]
 * does: `Ready=False` surfaces as `NotReady`, otherwise an active
 * pressure condition (`MemoryPressure` / `DiskPressure` / `PIDPressure`
 * / `NetworkUnavailable`) is promoted, falling back to `Ready` — or
 * `Unknown` when the Ready condition is absent entirely. Shared with
 * the node list-metrics stream so a streamed snapshot and the on-demand
 * `nodes` query never disagree on a node's status.
 */
internal fun K8sFabricNode.nodeStatusText(): String {
    val conditions = status?.conditions.orEmpty()
    val ready = conditions.firstOrNull { it.type == "Ready" }
    val pressure = conditions.firstOrNull {
        it.status == "True" && it.type in setOf("MemoryPressure", "DiskPressure", "PIDPressure", "NetworkUnavailable")
    }
    return when {
        ready == null -> "Unknown"
        ready.status != "True" -> "NotReady"
        pressure != null -> pressure.type
        else -> "Ready"
    }
}

/** Node role (`control-plane` / `worker`), shared with the node list-metrics stream. */
internal fun K8sFabricNode.nodeRole(): String = deriveRole(metadata?.labels.orEmpty())

private fun deriveRole(labels: Map<String, String>): String {
    if (labels.containsKey("node-role.kubernetes.io/control-plane")) return "control-plane"
    if (labels.containsKey("node-role.kubernetes.io/master")) return "control-plane"
    val explicit = labels.keys.firstOrNull { it.startsWith("node-role.kubernetes.io/") }
        ?.removePrefix("node-role.kubernetes.io/")
    return explicit ?: "worker"
}

private fun labelsAsJson(labels: Map<String, String>): JsonElement =
    JsonObject(labels.mapValues { JsonPrimitive(it.value) })

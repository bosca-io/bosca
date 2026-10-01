package bosca.kubernetes.controller.util

import bosca.kubernetes.controller.metrics.MetricsService
import bosca.kubernetes.model.Pod
import io.fabric8.kubernetes.api.model.Pod as K8sPod

/**
 * Maps a fabric8 `Pod` into our wire [Pod] shape.
 *
 * The status string is the raw kubelet phase except when a container
 * is in a known "stuck" state — `CrashLoopBackOff`, `ImagePullBackOff`,
 * `ErrImagePull`, `CreateContainerConfigError` — in which case we
 * surface that reason instead of the higher-level phase. This matches
 * how `kubectl get pods` displays status and gives the studio's
 * `PodStatusBadge` the granularity it expects.
 *
 * `workloadId` is the first ownerReference's UID — for `Deployment`-
 * driven pods that points at the ReplicaSet, not the Deployment.
 * Resolving the *top-level* owner means an additional lookup per pod
 * and is deferred until the informer caches can answer the question
 * in-process.
 *
 * Pass [usage] to fill in cpu (millicores) and memory (MiB). The
 * route fetches metrics in one shot via [MetricsService] and threads
 * them in — keeps the mapper a pure function and the metrics fetch
 * separable from the pod list when metrics-server is unavailable.
 */
fun K8sPod.toPodWire(usage: MetricsService.PodUsage? = null): Pod {
    val containers = spec?.containers.orEmpty()
    val containerStatuses = status?.containerStatuses.orEmpty()
    val readyCount = containerStatuses.count { it.ready == true }
    val totalCount = containers.size.coerceAtLeast(containerStatuses.size)
    val restarts = containerStatuses.sumOf { it.restartCount ?: 0 }

    val stuckReason = containerStatuses
        .asSequence()
        .mapNotNull { it.state?.waiting?.reason }
        .firstOrNull { it in STUCK_REASONS }
    val phase = status?.phase ?: "Unknown"

    val owner = metadata?.ownerReferences?.firstOrNull()
    return Pod(
        id = metadata?.uid ?: "${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        node = spec?.nodeName.orEmpty(),
        status = stuckReason ?: phase,
        ready = "$readyCount/$totalCount",
        restarts = restarts,
        age = formatAge(metadata?.creationTimestamp),
        cpu = usage?.cpuMillicores?.toInt() ?: 0,
        memory = ((usage?.memoryBytes ?: 0L) / BYTES_PER_MIB).toInt(),
        workloadId = owner?.uid,
        podIP = status?.podIP,
        hostIP = status?.hostIP,
        image = containers.firstOrNull()?.image.orEmpty(),
        workloadKind = owner?.kind,
        workloadName = owner?.name,
    )
}

private const val BYTES_PER_MIB: Long = 1024L * 1024L

private val STUCK_REASONS = setOf(
    "CrashLoopBackOff",
    "ImagePullBackOff",
    "ErrImagePull",
    "CreateContainerConfigError",
    "InvalidImageName",
    "ContainerCreating",
)

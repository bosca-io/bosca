package bosca.kubernetes.controller.util

import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.Job
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mappers from fabric8 model types to our wire [Workload] shape.
 *
 * Each extension produces the same shape regardless of upstream type
 * so the studio's workload table can render mixed-kind results
 * uniformly.
 *
 * Status distinguishes "not ready yet, but progressing" (PENDING) from
 * "not ready with failure evidence" (ERROR) by looking at the states of
 * the pods the route layer credits to each workload. Callers without a
 * pod list (e.g. the scale mutation's immediate response) fall back to
 * the coarse replica-count ladder, where zero-ready still reads ERROR.
 *
 * Live CPU / memory / restart aggregates come from the optional
 * [WorkloadAggregates] companion passed by the route layer. The
 * route owns the pod-list-and-ownership-resolution dance; the mapper
 * only consumes the already-bucketed totals.
 */

/**
 * Per-workload aggregates the route layer computes by walking the pod
 * → workload ownership chain. `cpu` is in cores (fractional;
 * matches the GraphQL wire field), `memory` is in GiB, `restarts` is
 * a plain sum over container statuses. [podStates] carries the pod
 * state summary the status ladder reads; `null` means the caller had
 * no pod visibility, not that the workload has no pods.
 */
data class WorkloadAggregates(
    val cpuCores: Double = 0.0,
    val memoryGiB: Double = 0.0,
    val restarts: Int = 0,
    val podStates: WorkloadPodStates? = null,
)

/**
 * States of the pods credited to one workload. `total` counts every
 * non-terminating credited pod; `pending` counts pods still in phase
 * `Pending` with no failure evidence; `failing` counts pods with a
 * container stuck in a known-bad waiting state or in phase `Failed`.
 * Running-but-not-ready pods (readiness probes still warming or
 * persistently failing) land in none of the two buckets.
 */
data class WorkloadPodStates(
    val total: Int = 0,
    val pending: Int = 0,
    val failing: Int = 0,
)

/**
 * Container waiting reasons that are failure evidence rather than
 * normal startup. Deliberately excludes `ContainerCreating` and
 * `PodInitializing` — those are the "pending" states this ladder
 * exists to stop reporting as failures.
 */
private val FAILING_WAITING_REASONS = setOf(
    "CrashLoopBackOff",
    "ImagePullBackOff",
    "ErrImagePull",
    "CreateContainerConfigError",
    "CreateContainerError",
    "RunContainerError",
    "InvalidImageName",
)

/**
 * Summarizes the states of a workload's credited pods. Terminating
 * pods (deletionTimestamp set) are skipped entirely — during a rollout
 * the outgoing generation is expected churn and must not color the
 * badge.
 */
fun podStatesOf(pods: List<Pod>): WorkloadPodStates {
    var total = 0
    var pending = 0
    var failing = 0
    for (pod in pods) {
        if (pod.metadata?.deletionTimestamp != null) continue
        total++
        val waitingReasons = (pod.status?.containerStatuses.orEmpty() + pod.status?.initContainerStatuses.orEmpty())
            .mapNotNull { it.state?.waiting?.reason }
        val phase = pod.status?.phase
        when {
            phase == "Failed" || waitingReasons.any { it in FAILING_WAITING_REASONS } -> failing++
            phase == "Pending" -> pending++
        }
    }
    return WorkloadPodStates(total = total, pending = pending, failing = failing)
}

/**
 * Builds `workload UID → pod state summary` by walking each pod up its
 * ownership chain — the same two-level walk the cpu/memory aggregates
 * use: a pod credits its direct owner, plus the Deployment above a
 * ReplicaSet and the CronJob above a Job, so a Deployment's badge sees
 * the pods of every generation's ReplicaSet.
 */
fun buildPodStateIndex(
    pods: List<Pod>,
    replicaSets: List<ReplicaSet>,
    jobs: List<Job>,
): Map<String, WorkloadPodStates> {
    val rsToDeployment: Map<String, String?> = replicaSets.associate { rs ->
        (rs.metadata?.uid ?: "") to rs.metadata?.ownerReferences
            ?.firstOrNull { it.kind == "Deployment" }?.uid
    }
    val jobToCronJob: Map<String, String?> = jobs.associate { j ->
        (j.metadata?.uid ?: "") to j.metadata?.ownerReferences
            ?.firstOrNull { it.kind == "CronJob" }?.uid
    }
    val byOwner = HashMap<String, MutableList<Pod>>()
    for (pod in pods) {
        val directUid = pod.metadata?.ownerReferences?.firstOrNull()?.uid ?: continue
        byOwner.getOrPut(directUid) { mutableListOf() }.add(pod)
        rsToDeployment[directUid]?.let { byOwner.getOrPut(it) { mutableListOf() }.add(pod) }
        jobToCronJob[directUid]?.let { byOwner.getOrPut(it) { mutableListOf() }.add(pod) }
    }
    return byOwner.mapValues { (_, owned) -> podStatesOf(owned) }
}

fun Deployment.toWorkload(aggregates: WorkloadAggregates = WorkloadAggregates()): Workload {
    val desired = spec?.replicas ?: 0
    val ready = status?.readyReplicas ?: 0
    return Workload(
        id = metadata?.uid ?: "Deployment/${metadata?.namespace}/${metadata?.name}",
        kind = WorkloadKind.DEPLOYMENT,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        ready = ready,
        desired = desired,
        status = workloadStatus(ready, desired, aggregates.podStates),
        image = primaryImage(spec?.template),
        age = formatAge(metadata?.creationTimestamp),
        cpu = aggregates.cpuCores,
        memory = aggregates.memoryGiB,
        restarts = aggregates.restarts,
        strategy = spec?.strategy?.type ?: "RollingUpdate",
        labels = labelsAsJson(metadata?.labels),
    )
}

fun StatefulSet.toWorkload(aggregates: WorkloadAggregates = WorkloadAggregates()): Workload {
    val desired = spec?.replicas ?: 0
    val ready = status?.readyReplicas ?: 0
    return Workload(
        id = metadata?.uid ?: "StatefulSet/${metadata?.namespace}/${metadata?.name}",
        kind = WorkloadKind.STATEFUL_SET,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        ready = ready,
        desired = desired,
        status = workloadStatus(ready, desired, aggregates.podStates),
        image = primaryImage(spec?.template),
        age = formatAge(metadata?.creationTimestamp),
        cpu = aggregates.cpuCores,
        memory = aggregates.memoryGiB,
        restarts = aggregates.restarts,
        strategy = spec?.updateStrategy?.type ?: "RollingUpdate",
        labels = labelsAsJson(metadata?.labels),
    )
}

fun DaemonSet.toWorkload(aggregates: WorkloadAggregates = WorkloadAggregates()): Workload {
    val desired = status?.desiredNumberScheduled ?: 0
    val ready = status?.numberReady ?: 0
    return Workload(
        id = metadata?.uid ?: "DaemonSet/${metadata?.namespace}/${metadata?.name}",
        kind = WorkloadKind.DAEMON_SET,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        ready = ready,
        desired = desired,
        status = workloadStatus(ready, desired, aggregates.podStates),
        image = primaryImage(spec?.template),
        age = formatAge(metadata?.creationTimestamp),
        cpu = aggregates.cpuCores,
        memory = aggregates.memoryGiB,
        restarts = aggregates.restarts,
        strategy = spec?.updateStrategy?.type ?: "RollingUpdate",
        labels = labelsAsJson(metadata?.labels),
    )
}

fun ReplicaSet.toWorkload(aggregates: WorkloadAggregates = WorkloadAggregates()): Workload {
    val desired = spec?.replicas ?: 0
    val ready = status?.readyReplicas ?: 0
    return Workload(
        id = metadata?.uid ?: "ReplicaSet/${metadata?.namespace}/${metadata?.name}",
        kind = WorkloadKind.REPLICA_SET,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        ready = ready,
        desired = desired,
        status = workloadStatus(ready, desired, aggregates.podStates),
        image = primaryImage(spec?.template),
        age = formatAge(metadata?.creationTimestamp),
        cpu = aggregates.cpuCores,
        memory = aggregates.memoryGiB,
        restarts = aggregates.restarts,
        strategy = "RollingUpdate",
        labels = labelsAsJson(metadata?.labels),
    )
}

fun Job.toWorkload(aggregates: WorkloadAggregates = WorkloadAggregates()): Workload {
    // For one-shot Jobs we treat `succeeded` as the ready count and
    // `completions` (default 1) as the desired count. An active Job
    // with no successes yet is reported as WARN; a failed Job (no
    // active, no succeeded, no desired left) is ERROR.
    val desired = spec?.completions ?: 1
    val succeeded = status?.succeeded ?: 0
    val active = status?.active ?: 0
    val ready = succeeded
    val effectiveStatus = when {
        succeeded >= desired -> WorkloadStatus.OK
        active > 0 -> WorkloadStatus.WARN
        else -> WorkloadStatus.ERROR
    }
    return Workload(
        id = metadata?.uid ?: "Job/${metadata?.namespace}/${metadata?.name}",
        kind = WorkloadKind.JOB,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        ready = ready,
        desired = desired,
        status = effectiveStatus,
        image = primaryImage(spec?.template),
        age = formatAge(metadata?.creationTimestamp),
        cpu = aggregates.cpuCores,
        memory = aggregates.memoryGiB,
        restarts = aggregates.restarts,
        strategy = "OnFailure",
        labels = labelsAsJson(metadata?.labels),
    )
}

fun CronJob.toWorkload(aggregates: WorkloadAggregates = WorkloadAggregates()): Workload {
    // CronJobs don't have a "ready" count in the same sense — the
    // running children are surfaced as `status.active`. A suspended
    // CronJob is reported as OK because the user explicitly stopped
    // it; an unsuspended CronJob is OK; only a CronJob with active >
    // 0 indicates an in-flight execution and is reported as WARN so
    // the studio's status badge surfaces that state.
    val activeCount = status?.active?.size ?: 0
    val effectiveStatus = if (activeCount > 0) WorkloadStatus.WARN else WorkloadStatus.OK
    return Workload(
        id = metadata?.uid ?: "CronJob/${metadata?.namespace}/${metadata?.name}",
        kind = WorkloadKind.CRON_JOB,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        ready = activeCount,
        desired = 1,
        status = effectiveStatus,
        image = primaryImage(spec?.jobTemplate?.spec?.template),
        age = formatAge(metadata?.creationTimestamp),
        cpu = aggregates.cpuCores,
        memory = aggregates.memoryGiB,
        restarts = aggregates.restarts,
        strategy = "Schedule(${spec?.schedule ?: "?"})",
        labels = labelsAsJson(metadata?.labels),
    )
}

/**
 * The status ladder for replica-driven kinds. With pod visibility:
 * failure evidence wins (ERROR when nothing serves, WARN when the
 * workload is partially serving alongside the failure), then pods
 * still coming up read PENDING, then a workload whose controller has
 * produced no pods at all (quota, admission webhook) reads ERROR, and
 * anything else — running-but-not-ready pods, partial readiness —
 * reads WARN. Without pod visibility (`pods == null`) we fall back to
 * the coarse replica-count ladder.
 */
private fun workloadStatus(ready: Int, desired: Int, pods: WorkloadPodStates? = null): WorkloadStatus = when {
    desired == 0 -> WorkloadStatus.OK
    ready >= desired -> WorkloadStatus.OK
    pods == null -> if (ready == 0) WorkloadStatus.ERROR else WorkloadStatus.WARN
    pods.failing > 0 -> if (ready == 0) WorkloadStatus.ERROR else WorkloadStatus.WARN
    pods.pending > 0 -> WorkloadStatus.PENDING
    pods.total == 0 -> WorkloadStatus.ERROR
    else -> WorkloadStatus.WARN
}

private fun primaryImage(template: PodTemplateSpec?): String =
    template?.spec?.containers?.firstOrNull()?.image.orEmpty()

private fun labelsAsJson(labels: Map<String, String>?): JsonElement? {
    if (labels.isNullOrEmpty()) return null
    return JsonObject(labels.mapValues { JsonPrimitive(it.value) })
}

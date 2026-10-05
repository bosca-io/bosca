package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * A single periodic CPU + memory sample for a pod, emitted on the
 * `k8sPodMetricsStream` GraphQL subscription. Values are normalised
 * the same way as the on-demand pod query: `cpuMillicores`
 * (millicores summed across containers), `memoryBytes` (raw bytes).
 *
 * The studio renders friendlier units (millicores → "n m", bytes →
 * "n MiB") from these primitives; keeping the wire shape in base
 * units avoids per-renderer rounding drift.
 */
@Serializable
data class PodMetricsSample(
    val namespace: String,
    val pod: String,
    /** Aggregate CPU usage across all containers, in millicores. */
    val cpuMillicores: Long,
    /** Aggregate memory usage across all containers, in bytes. */
    val memoryBytes: Long,
    /** ISO-8601 UTC timestamp the controller pulled the sample. */
    val timestamp: String,
)

/**
 * A single periodic CPU + memory sample for a node. Both raw usage
 * (millicores / bytes) and a 0–100 utilisation percentage against
 * the node's allocatable resources are emitted — the percentage
 * matches the on-demand `nodes` query so a node row's bar chart and
 * its detail-page live stream agree.
 */
@Serializable
data class NodeMetricsSample(
    val node: String,
    /** Raw CPU usage, millicores. */
    val cpuMillicores: Long,
    /** Raw memory usage, bytes. */
    val memoryBytes: Long,
    /** CPU utilisation against `status.allocatable.cpu`, 0–100. */
    val cpuPercent: Int,
    /** Memory utilisation against `status.allocatable.memory`, 0–100. */
    val memoryPercent: Int,
    /** ISO-8601 UTC timestamp the controller pulled the sample. */
    val timestamp: String,
)

/**
 * A cluster-wide aggregate sample — sums every pod's cpu / memory,
 * normalises against the cluster's total allocatable capacity, and
 * emits a single utilisation percentage suitable for an overview
 * dashboard's pulse chart.
 */
@Serializable
data class ClusterMetricsSample(
    /** Sum of all pod CPU usage, millicores. */
    val totalCpuMillicores: Long,
    /** Sum of all pod memory usage, bytes. */
    val totalMemoryBytes: Long,
    /** Cluster-wide CPU utilisation against allocatable, 0–100. */
    val cpuPercent: Int,
    /** Cluster-wide memory utilisation against allocatable, 0–100. */
    val memoryPercent: Int,
    /** Number of nodes contributing to this sample. */
    val nodeCount: Int,
    /** Number of pods contributing to this sample. */
    val podCount: Int,
    /** ISO-8601 UTC timestamp the controller pulled the sample. */
    val timestamp: String,
)

/**
 * One pod's live cpu/memory inside a list-scope snapshot. `id` is the
 * pod's stable kubernetes UID — matches the same value the
 * `kubernetes.pods` query returns so the studio can merge ticks into
 * existing rows without name + namespace gymnastics.
 */
@Serializable
data class PodMetricsListItem(
    val id: String,
    val namespace: String,
    val name: String,
    val cpuMillicores: Long,
    val memoryBytes: Long,
)

/**
 * Full snapshot of every pod's cpu/memory in a cluster (optionally
 * namespace-filtered), emitted once per tick on
 * `k8sPodsMetricsListStream`. Each tick carries the complete set so
 * the studio is fully self-correcting on a dropped frame and does not
 * need delta reconciliation.
 */
@Serializable
data class PodsMetricsListSample(
    val items: List<PodMetricsListItem>,
    val timestamp: String,
)

/**
 * One node's live cpu/memory inside a list-scope snapshot.
 *
 * `status` and `role` ride the same per-tick snapshot as the metrics so
 * an overview can track node readiness changes (a node going
 * `NotReady`, a new worker joining) over the already-open socket
 * without a second subscription. They mirror the on-demand `nodes`
 * query exactly: `status` is the derived condition text
 * (`Ready` / `NotReady` / a pressure type / `Unknown`) and `role` is
 * `control-plane` / `worker`.
 */
@Serializable
data class NodeMetricsListItem(
    val name: String,
    val cpuMillicores: Long,
    val memoryBytes: Long,
    val cpuPercent: Int,
    val memoryPercent: Int,
    val status: String,
    val role: String,
)

/** Snapshot of every node's cpu/memory, emitted on `k8sNodesMetricsListStream`. */
@Serializable
data class NodesMetricsListSample(
    val items: List<NodeMetricsListItem>,
    val timestamp: String,
)

/**
 * One workload's live cpu/memory aggregates inside a list-scope
 * snapshot. Aggregation walks ownership the same way
 * [bosca.kubernetes.controller.route.WorkloadsRoute] does:
 * Deployment ← ReplicaSet ← Pod and CronJob ← Job ← Pod, so a
 * Deployment row sums every pod across all of its ReplicaSets.
 *
 * `cpuCores` uses cores (fractional) and `memoryGiB` uses GiB to
 * match the units already on the `Workload` GraphQL type.
 */
@Serializable
data class WorkloadMetricsListItem(
    val id: String,
    val cpuCores: Double,
    val memoryGiB: Double,
    val restarts: Int,
)

/** Snapshot of every workload's aggregates, emitted on `k8sWorkloadsMetricsListStream`. */
@Serializable
data class WorkloadsMetricsListSample(
    val items: List<WorkloadMetricsListItem>,
    val timestamp: String,
)

/**
 * One workload's live status inside a list-scope snapshot. Carries just
 * the identity + coarse status badge — the lean counterpart to
 * [WorkloadMetricsListItem], emitted on `k8sWorkloadsStatusListStream`
 * so an overview can keep status badges live without opening a
 * per-workload watch for each row.
 *
 * Unlike the metrics list (whose rows are derived from pods that have
 * usage), this snapshot is built from the workload objects themselves,
 * so a scaled-to-zero or fully-failed workload still appears with its
 * real status rather than dropping out.
 */
@Serializable
data class WorkloadStatusListItem(
    /** Workload UID — matches `Workload.id` from the on-demand `workloads` query. */
    val id: String,
    val status: WorkloadStatus,
)

/** Snapshot of every workload's status, emitted on `k8sWorkloadsStatusListStream`. */
@Serializable
data class WorkloadsStatusListSample(
    val items: List<WorkloadStatusListItem>,
    val timestamp: String,
)

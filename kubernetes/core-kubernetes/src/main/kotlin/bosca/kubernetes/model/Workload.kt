package bosca.kubernetes.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Workload kinds the studio surfaces as first-class — every other CRD
 * is browsed through the generic Custom Resources page. The wire form
 * matches the GraphQL `WorkloadKind` enum exactly.
 */
@Serializable
enum class WorkloadKind {
    DEPLOYMENT, STATEFUL_SET, DAEMON_SET, REPLICA_SET, JOB, CRON_JOB,
}

/**
 * Coarse rollup the studio renders as a status badge. The mapping from
 * actual replica counts / pod phases lives controller-side so the
 * client doesn't have to reproduce the heuristics.
 *
 * [PENDING] separates "not ready yet, but progressing" (pods being
 * scheduled, images pulling, containers creating) from [ERROR], which
 * is reserved for workloads with actual failure evidence — a pod stuck
 * Pending is not a failing workload.
 */
@Serializable
enum class WorkloadStatus {
    OK, PENDING, WARN, ERROR,
}

/**
 * A workload (Deployment / StatefulSet / DaemonSet / ReplicaSet / Job /
 * CronJob) as surfaced by `kubernetes-controller`. The shape mirrors
 * the GraphQL `Workload` type — see
 * `kubernetes/src/main/resources/graphql/kubernetes/kubernetes.graphqls`.
 *
 * The `labels` field carries the raw label/annotation map as JSON so
 * the studio can render arbitrary user-defined labels without round-
 * tripping through a typed schema.
 */
@Serializable
data class Workload(
    val id: String,
    val kind: WorkloadKind,
    val name: String,
    val namespace: String,
    val ready: Int,
    val desired: Int,
    val status: WorkloadStatus,
    val image: String,
    val age: String,
    val cpu: Double,
    val memory: Double,
    val restarts: Int,
    val strategy: String,
    val labels: JsonElement? = null,
)

/** Wire envelope for `GET /clusters/{id}/workloads`. */
@Serializable
data class WorkloadsResponse(val items: List<Workload>)

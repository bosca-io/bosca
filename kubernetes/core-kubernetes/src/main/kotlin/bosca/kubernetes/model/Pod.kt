package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * A pod scheduled in a registered cluster, as surfaced by
 * `kubernetes-controller`. Shape mirrors the GraphQL `Pod` type. The
 * `status` field is the raw kubelet phase string (`Running`,
 * `Pending`, `CrashLoopBackOff`, …) — translation to a status badge
 * happens client-side.
 *
 * Owner workload reference is left as `workloadId` here; the
 * server-side resolver hydrates `Pod.workload` from the workload cache
 * when the client requests it.
 */
@Serializable
data class Pod(
    val id: String,
    val name: String,
    val namespace: String,
    val node: String,
    val status: String,
    val ready: String,
    val restarts: Int,
    val age: String,
    val cpu: Int,
    val memory: Int,
    val workloadId: String? = null,
    val podIP: String? = null,
    val hostIP: String? = null,
    /**
     * The pod's primary container image. Sourced from
     * `spec.containers[0].image` — the only intrinsic-to-the-pod
     * image; init / sidecar images are not surfaced separately yet.
     * Empty string when the pod has no containers (rare; e.g. during
     * creation).
     */
    val image: String = "",
    /**
     * Owner reference kind, e.g. "ReplicaSet", "StatefulSet",
     * "DaemonSet", "Job". Null when the pod has no owner (bare pod).
     */
    val workloadKind: String? = null,
    /**
     * Owner reference name, e.g. the ReplicaSet's name for
     * Deployment-spawned pods. Null when the pod has no owner.
     */
    val workloadName: String? = null,
)

/** Wire envelope for `GET /clusters/{id}/pods`. Carries total for paging. */
@Serializable
data class PodsResponse(
    val total: Int,
    val items: List<Pod>,
)

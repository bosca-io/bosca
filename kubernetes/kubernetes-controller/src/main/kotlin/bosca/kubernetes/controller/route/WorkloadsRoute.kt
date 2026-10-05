package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.metrics.MetricsService
import bosca.kubernetes.controller.util.WorkloadAggregates
import bosca.kubernetes.controller.util.WorkloadPodStates
import bosca.kubernetes.controller.util.buildPodStateIndex
import bosca.kubernetes.controller.util.toWorkload
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadsResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Lists workloads in the cluster identified by `{id}`.
 *
 * ## Authorization
 *
 * REQUIRED JWT plus an independent `administrators` group re-check
 * inside `execute()` — see [NamespacesRoute] for the rationale.
 *
 * ## Data path
 *
 * Always fans out to all six workload kinds (Deployment, StatefulSet,
 * DaemonSet, ReplicaSet, Job, CronJob) plus the cluster's pod list
 * and metrics, even when `kind` filters to a single workload type.
 * The reason: CPU / memory / restart counts have to be summed across
 * the pods that *belong* to a given workload, and that ownership chain
 * is two levels deep for the Deployment → ReplicaSet → Pod and
 * CronJob → Job → Pod paths. Fetching once at the top lets us build
 * the chain in one pass and apply the kind filter to the final list.
 *
 * Cluster-wide vs. namespaced: when `namespace` is omitted we use
 * `inAnyNamespace()` so admins get the cross-namespace overview the
 * studio's workload table renders by default. Specifying a namespace
 * issues a namespaced call so RBAC-restricted contexts (a future
 * per-namespace role) cost less and any namespace-scoped pods are
 * still aggregated correctly.
 */
@RouteController(
    path = "/clusters/{id}/workloads",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class WorkloadsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<WorkloadsResponse>() {

    override fun serializer(): KSerializer<WorkloadsResponse> = WorkloadsResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): WorkloadsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val params = call.request.queryParameters
        val namespaceFilter = params["namespace"]
        val kindFilter = params["kind"]?.let {
            runCatching { WorkloadKind.valueOf(it) }.getOrNull()
        }

        val client = pool.get(clusterId)
        val items = withContext(Dispatchers.IO) {
            try {
                fetchWorkloads(client, namespaceFilter, kindFilter)
            } catch (e: Exception) {
                log.warn("fabric8 workloads list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
        return WorkloadsResponse(items = items)
    }

    /**
     * Fetches every workload kind plus the pod list, walks the
     * ownership chain to assign each pod's restart / cpu / memory
     * contribution to the right top-level workload, then applies the
     * kind filter.
     */
    private fun fetchWorkloads(
        client: KubernetesClient,
        namespace: String?,
        kind: WorkloadKind?,
    ): List<Workload> {
        val deployments = list(client.apps().deployments(), namespace)
        val statefulSets = list(client.apps().statefulSets(), namespace)
        val daemonSets = list(client.apps().daemonSets(), namespace)
        val replicaSets = list(client.apps().replicaSets(), namespace)
        val jobs = list(client.batch().v1().jobs(), namespace)
        val cronJobs = list(client.batch().v1().cronjobs(), namespace)
        val pods = list(client.pods(), namespace)
        val podMetrics = MetricsService(client).podMetrics(namespace)

        val aggregates = buildAggregates(
            pods = pods,
            replicaSets = replicaSets,
            jobs = jobs,
            podMetrics = podMetrics,
        )

        val all = mutableListOf<Workload>()
        deployments.mapTo(all) { it.toWorkload(aggregates.forUid(it.metadata?.uid)) }
        statefulSets.mapTo(all) { it.toWorkload(aggregates.forUid(it.metadata?.uid)) }
        daemonSets.mapTo(all) { it.toWorkload(aggregates.forUid(it.metadata?.uid)) }
        replicaSets.mapTo(all) { it.toWorkload(aggregates.forUid(it.metadata?.uid)) }
        jobs.mapTo(all) { it.toWorkload(aggregates.forUid(it.metadata?.uid)) }
        cronJobs.mapTo(all) { it.toWorkload(aggregates.forUid(it.metadata?.uid)) }
        return if (kind == null) all else all.filter { it.kind == kind }
    }

    /**
     * Builds `workload UID → aggregates` by walking each pod up its
     * ownership chain:
     *
     *  * Pod → direct owner (always credited)
     *  * If direct owner is a ReplicaSet whose owner is a Deployment,
     *    the Deployment is also credited (so a Deployment row shows
     *    the sum across all its ReplicaSets' pods).
     *  * If direct owner is a Job whose owner is a CronJob, the
     *    CronJob is also credited.
     *
     * Pods owned by something not in the chain (bare pods, custom
     * controllers) contribute to their direct owner if it has a UID,
     * otherwise drop out — there's nothing useful to bucket them to.
     */
    private fun buildAggregates(
        pods: List<io.fabric8.kubernetes.api.model.Pod>,
        replicaSets: List<io.fabric8.kubernetes.api.model.apps.ReplicaSet>,
        jobs: List<io.fabric8.kubernetes.api.model.batch.v1.Job>,
        podMetrics: Map<String, MetricsService.PodUsage>,
    ): AggregateIndex {
        val rsToDeployment: Map<String, String?> = replicaSets.associate { rs ->
            (rs.metadata?.uid ?: "") to rs.metadata?.ownerReferences
                ?.firstOrNull { it.kind == "Deployment" }?.uid
        }
        val jobToCronJob: Map<String, String?> = jobs.associate { j ->
            (j.metadata?.uid ?: "") to j.metadata?.ownerReferences
                ?.firstOrNull { it.kind == "CronJob" }?.uid
        }

        val cpuSum = HashMap<String, Double>()
        val memSum = HashMap<String, Double>()
        val restartSum = HashMap<String, Int>()

        for (pod in pods) {
            val directUid = pod.metadata?.ownerReferences?.firstOrNull()?.uid ?: continue
            val restarts = pod.status?.containerStatuses.orEmpty()
                .sumOf { it.restartCount ?: 0 }
            val key = "${pod.metadata?.namespace.orEmpty()}/${pod.metadata?.name.orEmpty()}"
            val usage = podMetrics[key]
            val cpuCores = (usage?.cpuMillicores ?: 0L) / 1000.0
            val memGiB = (usage?.memoryBytes ?: 0L) / BYTES_PER_GIB.toDouble()

            credit(cpuSum, memSum, restartSum, directUid, cpuCores, memGiB, restarts)
            rsToDeployment[directUid]?.let { parent ->
                credit(cpuSum, memSum, restartSum, parent, cpuCores, memGiB, restarts)
            }
            jobToCronJob[directUid]?.let { parent ->
                credit(cpuSum, memSum, restartSum, parent, cpuCores, memGiB, restarts)
            }
        }

        return AggregateIndex(cpuSum, memSum, restartSum, buildPodStateIndex(pods, replicaSets, jobs))
    }

    private fun credit(
        cpu: HashMap<String, Double>,
        mem: HashMap<String, Double>,
        restarts: HashMap<String, Int>,
        uid: String,
        cpuDelta: Double,
        memDelta: Double,
        restartDelta: Int,
    ) {
        cpu.merge(uid, cpuDelta) { a, b -> a + b }
        mem.merge(uid, memDelta) { a, b -> a + b }
        restarts.merge(uid, restartDelta) { a, b -> a + b }
    }

    /**
     * Lazy lookup of aggregates with `WorkloadAggregates()` fallback.
     * `podStates` always resolves to a non-null summary when the uid is
     * known: this route listed the full pod set, so a workload with no
     * credited pods genuinely has zero pods (as opposed to callers
     * without pod visibility, which pass `null`).
     */
    private class AggregateIndex(
        private val cpu: Map<String, Double>,
        private val mem: Map<String, Double>,
        private val restarts: Map<String, Int>,
        private val podStates: Map<String, WorkloadPodStates>,
    ) {
        fun forUid(uid: String?): WorkloadAggregates {
            if (uid == null) return WorkloadAggregates()
            return WorkloadAggregates(
                cpuCores = cpu[uid] ?: 0.0,
                memoryGiB = mem[uid] ?: 0.0,
                restarts = restarts[uid] ?: 0,
                podStates = podStates[uid] ?: WorkloadPodStates(),
            )
        }
    }

    private fun <T : io.fabric8.kubernetes.api.model.HasMetadata, L : io.fabric8.kubernetes.api.model.KubernetesResourceList<T>> list(
        ops: io.fabric8.kubernetes.client.dsl.MixedOperation<T, L, *>,
        namespace: String?,
    ): List<T> {
        val listed = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
        return listed.items.orEmpty()
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkloadsRoute::class.java)
        private const val BYTES_PER_GIB: Long = 1024L * 1024L * 1024L
    }
}

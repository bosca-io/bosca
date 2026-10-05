package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.metrics.MetricsService
import bosca.kubernetes.controller.util.toPodWire
import bosca.kubernetes.model.PodsResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Lists pods in the cluster identified by `{id}`.
 *
 * Authorization mirrors [NamespacesRoute]: REQUIRED JWT + independent
 * admin re-check. Pod listings include node assignments and IPs —
 * privileged reconnaissance data — so administrators-only is the
 * floor.
 *
 * Optional query parameters mirror the GraphQL `pods` query:
 *   * `namespace`   — exact-match namespace filter
 *   * `workloadId`  — show only pods owned (directly or transitively)
 *                     by this workload. Walks one level up through
 *                     ReplicaSet → Deployment and Job → CronJob so
 *                     the studio can pass the top-level workload UID
 *                     it already has and get the pods that belong to
 *                     it without resolving the intermediate object.
 *   * `search`      — case-insensitive substring against pod name
 *   * `limit`       — page size (defaults to all items)
 *   * `offset`      — starting offset for pagination
 *
 * Pagination is applied **after** in-process filtering so the `total`
 * field reflects the filtered set, not the cluster's raw pod count.
 * That matches what the studio's `Showing N of M` indicator expects.
 */
@RouteController(
    path = "/clusters/{id}/pods",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class PodsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<PodsResponse>() {

    override fun serializer(): KSerializer<PodsResponse> = PodsResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): PodsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val params = call.request.queryParameters
        val namespaceFilter = params["namespace"]
        val workloadFilter = params["workloadId"]
        val search = params["search"]?.lowercase()
        val limit = params["limit"]?.toIntOrNull()
        val offset = params["offset"]?.toIntOrNull() ?: 0

        val client = pool.get(clusterId)
        val (rawPods, metrics, parents) = withContext(Dispatchers.IO) {
            try {
                val ops = client.pods()
                val list = if (namespaceFilter != null) {
                    ops.inNamespace(namespaceFilter).list()
                } else {
                    ops.inAnyNamespace().list()
                }
                // Pull live cpu/memory in the same coroutine so the
                // mapper has both inputs in hand. MetricsService
                // gracefully returns empty when metrics-server isn't
                // installed — pods still render, the cpu/memory
                // columns just stay at zero.
                val metricsMap = MetricsService(client).podMetrics(namespaceFilter)
                // Build the ReplicaSet→Deployment and Job→CronJob
                // parent map only when needed for filtering. The studio
                // sends the *top-level* workload UID (Deployment /
                // CronJob), but a pod's direct ownerReference is the
                // intermediate (ReplicaSet / Job). Without this walk
                // a workload-detail page filter would never match.
                val parentMap = if (workloadFilter != null) {
                    buildOwnerParentMap(client, namespaceFilter)
                } else {
                    emptyMap()
                }
                Triple(list.items, metricsMap, parentMap)
            } catch (e: Exception) {
                log.warn("fabric8 pods list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }

        val filteredRaw = rawPods
            .let { src -> workloadFilter?.let { wid -> src.filter { matchesWorkload(it, wid, parents) } } ?: src }

        val pods = filteredRaw
            .map { pod -> pod.toPodWire(usage = metrics["${pod.metadata?.namespace}/${pod.metadata?.name}"]) }
            .let { src -> search?.let { q -> src.filter { q in it.name.lowercase() } } ?: src }

        val total = pods.size
        val paged = if (limit != null) pods.drop(offset).take(limit) else pods.drop(offset)
        return PodsResponse(total = total, items = paged)
    }

    /**
     * Returns true when [pod] is owned (directly or transitively) by
     * the workload identified by [workloadUid]. Walks one level up
     * through [parents] (ReplicaSet → Deployment, Job → CronJob).
     */
    private fun matchesWorkload(pod: Pod, workloadUid: String, parents: Map<String, String>): Boolean {
        val directUid = pod.metadata?.ownerReferences?.firstOrNull()?.uid ?: return false
        if (directUid == workloadUid) return true
        return parents[directUid] == workloadUid
    }

    /**
     * Builds a UID → top-level-workload-UID map by listing
     * ReplicaSets and Jobs and reading their first ownerReference.
     * Each list is wrapped in `runCatching` so the map gracefully
     * degrades to whichever lists succeeded (matches how
     * MetricsService handles a missing API).
     */
    private fun buildOwnerParentMap(client: KubernetesClient, namespace: String?): Map<String, String> {
        val parents = HashMap<String, String>()
        runCatching {
            val rsList = if (namespace != null)
                client.apps().replicaSets().inNamespace(namespace).list().items
            else
                client.apps().replicaSets().inAnyNamespace().list().items
            for (rs in rsList) {
                val uid = rs.metadata?.uid ?: continue
                val parent = rs.metadata?.ownerReferences
                    ?.firstOrNull { it.kind == "Deployment" }?.uid ?: continue
                parents[uid] = parent
            }
        }.onFailure { e -> log.debug("replicaSets list failed for owner walk: {}", e.message) }

        runCatching {
            val jobList = if (namespace != null)
                client.batch().v1().jobs().inNamespace(namespace).list().items
            else
                client.batch().v1().jobs().inAnyNamespace().list().items
            for (job in jobList) {
                val uid = job.metadata?.uid ?: continue
                val parent = job.metadata?.ownerReferences
                    ?.firstOrNull { it.kind == "CronJob" }?.uid ?: continue
                parents[uid] = parent
            }
        }.onFailure { e -> log.debug("jobs list failed for owner walk: {}", e.message) }

        return parents
    }

    companion object {
        private val log = LoggerFactory.getLogger(PodsRoute::class.java)
    }
}

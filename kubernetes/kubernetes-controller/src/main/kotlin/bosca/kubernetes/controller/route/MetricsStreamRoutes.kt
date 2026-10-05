package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.metrics.MetricsService
import bosca.kubernetes.controller.util.WorkloadAggregates
import bosca.kubernetes.controller.util.WorkloadPodStates
import bosca.kubernetes.controller.util.buildPodStateIndex
import bosca.kubernetes.controller.util.nodeRole
import bosca.kubernetes.controller.util.nodeStatusText
import bosca.kubernetes.controller.util.toWorkload
import bosca.kubernetes.model.ClusterMetricsSample
import bosca.kubernetes.model.NodeMetricsListItem
import bosca.kubernetes.model.NodeMetricsSample
import bosca.kubernetes.model.NodesMetricsListSample
import bosca.kubernetes.model.PodMetricsListItem
import bosca.kubernetes.model.PodMetricsSample
import bosca.kubernetes.model.PodsMetricsListSample
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadMetricsListItem
import bosca.kubernetes.model.WorkloadStatusListItem
import bosca.kubernetes.model.WorkloadsMetricsListSample
import bosca.kubernetes.model.WorkloadsStatusListSample
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Periodic CPU + memory samples streamed as NDJSON.
 *
 * Each route in this file follows the same shape: authenticate,
 * resolve the cluster, then loop — pull `metrics.k8s.io` via
 * [MetricsService], emit one sample per tick, sleep for the
 * caller-configurable interval. The stream stays open until the
 * client disconnects (the coroutine's `isActive` flips to false) or
 * the route's coroutine is otherwise cancelled.
 *
 * If metrics-server isn't installed on the target cluster every
 * sample reports zero; the route still emits at the requested
 * cadence so the studio's UI keeps rendering. A one-time WARN logs
 * the missing API per cluster — see [MetricsService] for the
 * dedup logic.
 *
 * The studio subscribes via GraphQL subscriptions
 * `k8sPodMetricsStream` / `k8sNodeMetricsStream` /
 * `k8sClusterMetricsStream`, which forward to these routes through
 * the same JWT-signed client used by the other streams.
 */
private val NDJSON = ContentType("application", "x-ndjson")
private const val DEFAULT_INTERVAL_SEC = 5
private const val MIN_INTERVAL_SEC = 1
private const val MAX_INTERVAL_SEC = 60
private val log = LoggerFactory.getLogger("bosca.kubernetes.controller.route.MetricsStream")

private fun parseInterval(raw: String?): Long {
    val seconds = raw?.toIntOrNull() ?: DEFAULT_INTERVAL_SEC
    return seconds.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC).toLong()
}

/**
 * Pumps NDJSON `T` records into the streaming HTTP body until the
 * coroutine is cancelled. Each tick produces one record (or zero —
 * `produce` may return null to skip a tick, e.g. when the target
 * pod has not been observed yet).
 */
private suspend fun <T : Any> streamSamples(
    call: ServerCall,
    intervalSec: Long,
    serializer: KSerializer<T>,
    json: Json,
    produce: () -> T?,
) {
    call.response.respondStreaming(NDJSON, HttpStatusCode.OK) { output ->
        while (currentCoroutineContext().isActive) {
            val sample = withContext(Dispatchers.IO) { runCatching(produce).getOrNull() }
            if (sample != null) {
                val bytes = (json.encodeToString(serializer, sample) + "\n").toByteArray(Charsets.UTF_8)
                output.write(bytes)
                output.flush()
            }
            delay(intervalSec.seconds)
        }
    }
}

private fun parseMillicores(q: Quantity?): Long = MetricsService.parseCpuMillicores(q)
private fun parseBytes(q: Quantity?): Long = MetricsService.parseMemoryBytes(q)

private fun percentOf(used: Long, allocatable: Long): Int {
    if (used <= 0 || allocatable <= 0) return 0
    return ((used * 100.0) / allocatable).toInt().coerceIn(0, 100)
}

/**
 * Streams one `PodMetricsSample` per tick for a single pod. The
 * metric values come from `metrics.k8s.io/v1beta1/namespaces/{ns}/pods/{name}`;
 * a 404 there means metrics-server is missing — we still emit a
 * zero-valued sample so the studio renders an explicit "0 m / 0 MiB"
 * row instead of looking frozen.
 */
@RouteController(
    path = "/clusters/{id}/pods/{namespace}/{name}/metrics/stream",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class PodMetricsStreamRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val podName = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val interval = parseInterval(call.request.queryParameters["intervalSec"])
        val client = pool.get(clusterId)
        val metrics = MetricsService(client)

        streamSamples(call, interval, PodMetricsSample.serializer(), json) {
            val usage = metrics.podMetrics(namespace)["$namespace/$podName"]
            PodMetricsSample(
                namespace = namespace,
                pod = podName,
                cpuMillicores = usage?.cpuMillicores ?: 0L,
                memoryBytes = usage?.memoryBytes ?: 0L,
                timestamp = Instant.now().toString(),
            )
        }
        return Unit
    }
}

/**
 * Streams one `NodeMetricsSample` per tick. With no `name` path
 * param this is the cluster-wide stream — one sample per node per
 * tick (flattened); with a name it narrows to one node. The
 * utilisation percentage is computed against the node's
 * `status.allocatable` snapshot captured once at the top of each
 * tick so it stays current as the allocatable bag changes.
 */
@RouteController(
    path = "/clusters/{id}/nodes/{name}/metrics/stream",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class NodeMetricsStreamRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val nodeName = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val interval = parseInterval(call.request.queryParameters["intervalSec"])
        val client = pool.get(clusterId)
        val metrics = MetricsService(client)

        streamSamples(call, interval, NodeMetricsSample.serializer(), json) {
            val node = runCatching { client.nodes().withName(nodeName).get() }.getOrNull()
            val usage = metrics.nodeMetrics()[nodeName]
            val allocatable = node?.status?.allocatable.orEmpty()
            val cpuMc = usage?.cpuMillicores ?: 0L
            val memB = usage?.memoryBytes ?: 0L
            NodeMetricsSample(
                node = nodeName,
                cpuMillicores = cpuMc,
                memoryBytes = memB,
                cpuPercent = percentOf(cpuMc, parseMillicores(allocatable["cpu"])),
                memoryPercent = percentOf(memB, parseBytes(allocatable["memory"])),
                timestamp = Instant.now().toString(),
            )
        }
        return Unit
    }
}

/**
 * Streams one `ClusterMetricsSample` per tick — a single aggregate
 * across every node in the cluster, suitable for an overview pulse.
 * Sums every pod's usage, divides by cluster-wide allocatable. A
 * cluster with no pods reports zero (not NaN); a cluster with no
 * allocatable reports zero too (would be NaN from the divide).
 */
@RouteController(
    path = "/clusters/{id}/metrics/stream",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class ClusterMetricsStreamRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val interval = parseInterval(call.request.queryParameters["intervalSec"])
        val client = pool.get(clusterId)
        val metrics = MetricsService(client)

        streamSamples(call, interval, ClusterMetricsSample.serializer(), json) {
            clusterSample(client, metrics)
        }
        return Unit
    }

    private fun clusterSample(client: KubernetesClient, metrics: MetricsService): ClusterMetricsSample {
        val nodes = runCatching { client.nodes().list().items }.getOrElse { emptyList() }
        val pods = runCatching { client.pods().inAnyNamespace().list().items }.getOrElse { emptyList() }
        val nodeUsages = metrics.nodeMetrics()

        val totalCpuMc = nodeUsages.values.sumOf { it.cpuMillicores }
        val totalMemB = nodeUsages.values.sumOf { it.memoryBytes }
        val totalAllocCpu = nodes.sumOf { parseMillicores(it.status?.allocatable?.get("cpu")) }
        val totalAllocMem = nodes.sumOf { parseBytes(it.status?.allocatable?.get("memory")) }

        return ClusterMetricsSample(
            totalCpuMillicores = totalCpuMc,
            totalMemoryBytes = totalMemB,
            cpuPercent = percentOf(totalCpuMc, totalAllocCpu),
            memoryPercent = percentOf(totalMemB, totalAllocMem),
            nodeCount = nodes.size,
            podCount = pods.size,
            timestamp = Instant.now().toString(),
        )
    }
}

/**
 * Streams a full per-pod cpu/memory snapshot per tick for the cluster
 * (optionally narrowed by namespace). Each tick carries the complete
 * set so the studio can merge by pod UID into existing rows without
 * delta reconciliation — a dropped frame self-corrects on the next.
 *
 * Why a list stream rather than N per-pod streams: the studio's pods /
 * workloads / nodes index pages need one socket per page, not one per
 * row. A 100-pod cluster sampled every 5s with N streams would burn
 * 100 WebSocket connections; one list stream is one connection and
 * one upstream metrics-server call per tick.
 */
@RouteController(
    path = "/clusters/{id}/pods/metrics/list-stream",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class PodsMetricsListStreamRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val interval = parseInterval(call.request.queryParameters["intervalSec"])
        val client = pool.get(clusterId)
        val metrics = MetricsService(client)

        streamSamples(call, interval, PodsMetricsListSample.serializer(), json) {
            val pods = runCatching {
                if (namespace != null) {
                    client.pods().inNamespace(namespace).list().items
                } else {
                    client.pods().inAnyNamespace().list().items
                }
            }.getOrElse { emptyList() }
            val usages = metrics.podMetrics(namespace)
            val items = pods.map { pod ->
                val ns = pod.metadata?.namespace.orEmpty()
                val name = pod.metadata?.name.orEmpty()
                val usage = usages["$ns/$name"]
                PodMetricsListItem(
                    id = pod.metadata?.uid ?: "$ns/$name",
                    namespace = ns,
                    name = name,
                    cpuMillicores = usage?.cpuMillicores ?: 0L,
                    memoryBytes = usage?.memoryBytes ?: 0L,
                )
            }
            PodsMetricsListSample(items = items, timestamp = Instant.now().toString())
        }
        return Unit
    }
}

/**
 * Streams a per-node cpu/memory snapshot per tick — same shape as the
 * per-pod list stream, but keyed by node name (nodes have no
 * meaningful UID in any of the studio's existing views).
 */
@RouteController(
    path = "/clusters/{id}/nodes/metrics/list-stream",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class NodesMetricsListStreamRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val interval = parseInterval(call.request.queryParameters["intervalSec"])
        val client = pool.get(clusterId)
        val metrics = MetricsService(client)

        streamSamples(call, interval, NodesMetricsListSample.serializer(), json) {
            val nodes = runCatching { client.nodes().list().items }.getOrElse { emptyList() }
            val usages = metrics.nodeMetrics()
            val items = nodes.map { node ->
                val name = node.metadata?.name.orEmpty()
                val usage = usages[name]
                val allocatable = node.status?.allocatable.orEmpty()
                val cpuMc = usage?.cpuMillicores ?: 0L
                val memB = usage?.memoryBytes ?: 0L
                NodeMetricsListItem(
                    name = name,
                    cpuMillicores = cpuMc,
                    memoryBytes = memB,
                    cpuPercent = percentOf(cpuMc, parseMillicores(allocatable["cpu"])),
                    memoryPercent = percentOf(memB, parseBytes(allocatable["memory"])),
                    status = node.nodeStatusText(),
                    role = node.nodeRole(),
                )
            }
            NodesMetricsListSample(items = items, timestamp = Instant.now().toString())
        }
        return Unit
    }
}

/**
 * Streams per-workload cpu/memory + restart aggregates per tick.
 *
 * Aggregation walks the same ownership chain as
 * [bosca.kubernetes.controller.route.WorkloadsRoute]: each pod's
 * usage credits its direct owner, and additionally credits a
 * Deployment (when the direct owner is a ReplicaSet that points at
 * one) or a CronJob (when the direct owner is a Job that points at
 * one). A Deployment row sums every pod across all of its
 * ReplicaSets — same semantics the on-demand `workloads` query
 * already implements.
 *
 * Units mirror the `Workload` GraphQL type: cores (fractional), GiB.
 */
@RouteController(
    path = "/clusters/{id}/workloads/metrics/list-stream",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class WorkloadsMetricsListStreamRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val interval = parseInterval(call.request.queryParameters["intervalSec"])
        val client = pool.get(clusterId)
        val metrics = MetricsService(client)

        streamSamples(call, interval, WorkloadsMetricsListSample.serializer(), json) {
            val pods = runCatching {
                if (namespace != null) client.pods().inNamespace(namespace).list().items
                else client.pods().inAnyNamespace().list().items
            }.getOrElse { emptyList() }
            val replicaSets = runCatching {
                if (namespace != null) client.apps().replicaSets().inNamespace(namespace).list().items
                else client.apps().replicaSets().inAnyNamespace().list().items
            }.getOrElse { emptyList() }
            val jobs = runCatching {
                if (namespace != null) client.batch().v1().jobs().inNamespace(namespace).list().items
                else client.batch().v1().jobs().inAnyNamespace().list().items
            }.getOrElse { emptyList() }
            val usages = metrics.podMetrics(namespace)

            val rsToDeployment = replicaSets.associate { rs ->
                (rs.metadata?.uid ?: "") to rs.metadata?.ownerReferences
                    ?.firstOrNull { it.kind == "Deployment" }?.uid
            }
            val jobToCronJob = jobs.associate { j ->
                (j.metadata?.uid ?: "") to j.metadata?.ownerReferences
                    ?.firstOrNull { it.kind == "CronJob" }?.uid
            }

            val cpu = HashMap<String, Double>()
            val mem = HashMap<String, Double>()
            val restarts = HashMap<String, Int>()
            for (pod in pods) {
                val directUid = pod.metadata?.ownerReferences?.firstOrNull()?.uid ?: continue
                val r = pod.status?.containerStatuses.orEmpty().sumOf { it.restartCount ?: 0 }
                val key = "${pod.metadata?.namespace.orEmpty()}/${pod.metadata?.name.orEmpty()}"
                val u = usages[key]
                val cpuCores = (u?.cpuMillicores ?: 0L) / 1000.0
                val memGiB = (u?.memoryBytes ?: 0L) / BYTES_PER_GIB.toDouble()
                fun credit(uid: String) {
                    cpu.merge(uid, cpuCores) { a, b -> a + b }
                    mem.merge(uid, memGiB) { a, b -> a + b }
                    restarts.merge(uid, r) { a, b -> a + b }
                }
                credit(directUid)
                rsToDeployment[directUid]?.let(::credit)
                jobToCronJob[directUid]?.let(::credit)
            }

            val ids = cpu.keys union mem.keys union restarts.keys
            val items = ids.map { id ->
                WorkloadMetricsListItem(
                    id = id,
                    cpuCores = cpu[id] ?: 0.0,
                    memoryGiB = mem[id] ?: 0.0,
                    restarts = restarts[id] ?: 0,
                )
            }
            WorkloadsMetricsListSample(items = items, timestamp = Instant.now().toString())
        }
        return Unit
    }

    companion object {
        private const val BYTES_PER_GIB: Long = 1024L * 1024L * 1024L
    }
}

/**
 * Streams a per-workload status snapshot per tick — the lean
 * counterpart to [WorkloadsMetricsListStreamRoute]. Where the metrics
 * stream derives its rows from pods that have usage (a scaled-to-zero
 * workload contributes no pod and so never appears), this stream is
 * built from the workload objects themselves, so every workload —
 * including failed or zero-replica ones — surfaces with its real
 * status badge.
 *
 * Status comes from the same `toWorkload` mapper the on-demand
 * `workloads` query uses, so a streamed badge and a freshly-listed
 * badge never disagree — which is why each tick also lists pods: the
 * status ladder needs pod states to tell PENDING from ERROR. Pod
 * *metrics* are still intentionally NOT fetched here (status doesn't
 * depend on cpu/memory), keeping each tick cheap relative to the
 * metrics list stream.
 */
@RouteController(
    path = "/clusters/{id}/workloads/status/list-stream",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class WorkloadsStatusListStreamRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val interval = parseInterval(call.request.queryParameters["intervalSec"])
        val client = pool.get(clusterId)

        streamSamples(call, interval, WorkloadsStatusListSample.serializer(), json) {
            val items = listWorkloads(client, namespace)
                .map { WorkloadStatusListItem(id = it.id, status = it.status) }
            WorkloadsStatusListSample(items = items, timestamp = Instant.now().toString())
        }
        return Unit
    }

    /**
     * Lists all six first-class workload kinds plus the pod list and
     * maps each through `toWorkload` with the pod-state summary the
     * status ladder needs (cpu/memory aggregates stay empty — status
     * doesn't depend on them). A failed list for one kind degrades to
     * an empty slice rather than collapsing the whole tick; a failed
     * pod list degrades to the coarse replica-count ladder.
     */
    private fun listWorkloads(client: KubernetesClient, namespace: String?): List<Workload> {
        fun <T : io.fabric8.kubernetes.api.model.HasMetadata, L : io.fabric8.kubernetes.api.model.KubernetesResourceList<T>> items(
            ops: io.fabric8.kubernetes.client.dsl.MixedOperation<T, L, *>,
        ): List<T> = runCatching {
            (if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list())
                .items.orEmpty()
        }.getOrElse { emptyList() }

        val replicaSets = items(client.apps().replicaSets())
        val jobs = items(client.batch().v1().jobs())
        // A failed pod list must yield a null index (→ coarse ladder),
        // not an empty one — an empty index claims every workload has
        // zero pods, which the ladder reads as failure evidence.
        val podStateIndex = runCatching {
            val pods = (if (namespace != null) client.pods().inNamespace(namespace).list() else client.pods().inAnyNamespace().list())
                .items.orEmpty()
            buildPodStateIndex(pods, replicaSets, jobs)
        }.getOrNull()
        fun aggregatesFor(uid: String?) = WorkloadAggregates(
            podStates = if (podStateIndex == null || uid == null) null else podStateIndex[uid] ?: WorkloadPodStates(),
        )

        val all = mutableListOf<Workload>()
        items(client.apps().deployments()).mapTo(all) { it.toWorkload(aggregatesFor(it.metadata?.uid)) }
        items(client.apps().statefulSets()).mapTo(all) { it.toWorkload(aggregatesFor(it.metadata?.uid)) }
        items(client.apps().daemonSets()).mapTo(all) { it.toWorkload(aggregatesFor(it.metadata?.uid)) }
        replicaSets.mapTo(all) { it.toWorkload(aggregatesFor(it.metadata?.uid)) }
        jobs.mapTo(all) { it.toWorkload(aggregatesFor(it.metadata?.uid)) }
        items(client.batch().v1().cronjobs()).mapTo(all) { it.toWorkload(aggregatesFor(it.metadata?.uid)) }
        return all
    }
}

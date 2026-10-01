package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.cnpgInstanceFromStatus
import bosca.kubernetes.controller.util.toCnpgBackup
import bosca.kubernetes.controller.util.toCnpgCluster
import bosca.kubernetes.controller.util.toCnpgClusterDetail
import bosca.kubernetes.controller.util.toCnpgInstance
import bosca.kubernetes.model.CnpgClusterDetailResponse
import bosca.kubernetes.model.CnpgClustersResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

private const val CNPG_GROUP = "postgresql.cnpg.io"
private const val CNPG_V1 = "v1"

private fun cnpgContext(kind: String) = ResourceDefinitionContext.Builder()
    .withGroup(CNPG_GROUP)
    .withVersion(CNPG_V1)
    .withKind(kind)
    .withNamespaced(true)
    .withPlural(when (kind) {
        "Cluster" -> "clusters"
        "ScheduledBackup" -> "scheduledbackups"
        "Backup" -> "backups"
        "Pooler" -> "poolers"
        else -> "${kind.lowercase()}s"
    })
    .build()

/**
 * Splits "CRD not installed" (404 — fine, debug) from "the SA can't
 * list this CRD" (403 — surface at WARN so the operator can see
 * why the CNPG page is empty) from "real failure" (also WARN). The
 * page renders empty either way; this just makes the cause findable
 * in logs.
 */
private fun logCnpgListFailure(kind: String, clusterId: UUID, t: Throwable) {
    val log = LoggerFactory.getLogger("bosca.kubernetes.controller.route.Cnpg")
    when {
        t is KubernetesClientException && t.code == 404 ->
            log.debug("CNPG {} CRD not installed on cluster {}", kind, clusterId)
        t is KubernetesClientException && t.code == 403 ->
            log.warn(
                "CNPG {} list forbidden on cluster {} — kubeconfig SA needs `list` rights on postgresql.cnpg.io",
                kind, clusterId,
            )
        else ->
            log.warn("CNPG {} list failed on cluster {}: {}", kind, clusterId, t.message)
    }
}

/**
 * Lists CNPG `Cluster` resources, with backup schedule + last-backup
 * timestamps stitched in from sibling `ScheduledBackup` and `Backup`
 * CRDs.
 *
 *  * Each list is a single fabric8 call; the three are coalesced
 *    in-memory rather than fanning out per cluster.
 *  * Missing CRDs (CNPG not installed) yield an empty response — same
 *    graceful-degradation pattern as cert-manager and Cilium.
 *  * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/cnpg/clusters",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class CnpgClustersRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<CnpgClustersResponse>() {

    override fun serializer(): KSerializer<CnpgClustersResponse> = CnpgClustersResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): CnpgClustersResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)

        return withContext(Dispatchers.IO) {
            val clusters = runCatching {
                val ops = client.genericKubernetesResources(cnpgContext("Cluster"))
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                list.items.orEmpty()
            }.getOrElse {
                logCnpgListFailure("Cluster", clusterId, it)
                return@withContext CnpgClustersResponse(items = emptyList())
            }

            val schedules = scheduleByCluster(client, clusterId, namespace)
            val lastBackups = lastBackupByCluster(client, clusterId, namespace)
            CnpgClustersResponse(items = clusters.map { it.toCnpgCluster(schedules, lastBackups) })
        }
    }

    /**
     * One ScheduledBackup list call, indexed by `(namespace, clusterName)`
     * so each Cluster row resolves with a constant-time map lookup.
     * Returns empty when the CRD isn't installed or the SA can't list
     * it (the cluster list itself still renders).
     */
    @Suppress("UNCHECKED_CAST")
    private fun scheduleByCluster(client: KubernetesClient, clusterId: UUID, namespace: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            val ops = client.genericKubernetesResources(cnpgContext("ScheduledBackup"))
            val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
            for (sb in list.items.orEmpty()) {
                val spec = sb.additionalProperties["spec"] as? Map<String, Any?> ?: continue
                val schedule = spec["schedule"] as? String ?: continue
                val clusterRef = (spec["cluster"] as? Map<String, Any?>)?.get("name") as? String ?: continue
                val key = "${sb.metadata?.namespace.orEmpty()}/$clusterRef"
                map.putIfAbsent(key, schedule)
            }
        }.onFailure { logCnpgListFailure("ScheduledBackup", clusterId, it) }
        return map
    }

    /**
     * One Backup list call, grouped by `(namespace, clusterName)`,
     * picking the most recent successful backup per cluster. CNPG's
     * Backup CRD has `status.phase = "completed"` for success.
     */
    @Suppress("UNCHECKED_CAST")
    private fun lastBackupByCluster(client: KubernetesClient, clusterId: UUID, namespace: String?): Map<String, OffsetDateTime> {
        val map = mutableMapOf<String, OffsetDateTime>()
        runCatching {
            val ops = client.genericKubernetesResources(cnpgContext("Backup"))
            val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
            for (b in list.items.orEmpty()) {
                val status = b.additionalProperties["status"] as? Map<String, Any?> ?: continue
                val phase = status["phase"] as? String
                if (!phase.equals("completed", ignoreCase = true)) continue
                val stoppedAt = (status["stoppedAt"] as? String)
                    ?: (status["startedAt"] as? String)
                    ?: continue
                val parsed = try {
                    OffsetDateTime.parse(stoppedAt)
                } catch (_: DateTimeParseException) {
                    continue
                }
                val spec = b.additionalProperties["spec"] as? Map<String, Any?> ?: continue
                val clusterRef = (spec["cluster"] as? Map<String, Any?>)?.get("name") as? String ?: continue
                val key = "${b.metadata?.namespace.orEmpty()}/$clusterRef"
                val existing = map[key]
                if (existing == null || parsed.isAfter(existing)) {
                    map[key] = parsed
                }
            }
        }.onFailure { logCnpgListFailure("Backup", clusterId, it) }
        return map
    }

    companion object {
        private val log = LoggerFactory.getLogger(CnpgClustersRoute::class.java)
    }
}

/**
 * Full detail for a single CNPG `Cluster`: summary row + instance
 * topology + backup history + tuned parameters + storage/backup spec.
 *
 *  * The instance topology is composed from the operator's
 *    `cnpg.io/cluster=<name>` pods, joined to node zone labels and the
 *    PVC bound to each instance — all single list calls, no per-pod
 *    fan-out.
 *  * Returns `detail = null` when the named Cluster doesn't exist (the
 *    studio renders a "not found" state). Each sibling read
 *    (ScheduledBackup / Backup / pods / nodes / PVCs) degrades to empty
 *    independently — a missing CRD or an RBAC gap on one of them never
 *    blanks the whole page.
 *  * REQUIRED JWT + admin re-check, identical to [CnpgClustersRoute].
 */
@RouteController(
    path = "/clusters/{id}/cnpg/clusters/{namespace}/{name}",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class CnpgClusterDetailRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<CnpgClusterDetailResponse>() {

    override fun serializer(): KSerializer<CnpgClusterDetailResponse> = CnpgClusterDetailResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): CnpgClusterDetailResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"] ?: return null
        val name = call.pathParameters["name"] ?: return null
        val client = pool.get(clusterId)

        return withContext(Dispatchers.IO) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val clusterRes = getCluster(client, clusterId, namespace, name)
                ?: return@withContext CnpgClusterDetailResponse(detail = null)

            val rawBackups = listClusterBackups(client, clusterId, namespace, name)
            val backups = rawBackups
                .sortedByDescending { parseInstant(it.metadata?.creationTimestamp) }
                .map { it.toCnpgBackup(now) }

            val scheduleMap = scheduleFor(client, clusterId, namespace, name)
            val lastBackupMap = lastBackupFor(rawBackups, namespace, name)
            val summary = clusterRes.toCnpgCluster(scheduleMap, lastBackupMap, now)

            val currentPrimary = currentPrimaryOf(clusterRes)
            val zoneByNode = zoneByNode(client, clusterId)
            val pvcSizeByInstance = pvcSizeByInstance(client, clusterId, namespace)

            // The instance list is sourced from the Cluster status
            // (always readable here — it's the same CR that supplies
            // storage/params) so the table never blanks out when the
            // kubeconfig can read CNPG CRDs but not core pods. Labelled
            // pods, when reachable, enrich each row with node / zone /
            // readiness; their names are unioned in so a pod the status
            // hasn't caught up to yet still appears.
            val podsByName = listInstancePods(client, clusterId, namespace, name)
                .associateBy { it.metadata?.name.orEmpty() }
                .filterKeys { it.isNotEmpty() }
            val reportedPrimary = reportedPrimaryOf(clusterRes)
            val instances = (instanceNamesOf(clusterRes) + podsByName.keys)
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .map { instanceName ->
                    podsByName[instanceName]?.toCnpgInstance(currentPrimary, zoneByNode, pvcSizeByInstance, now)
                        ?: cnpgInstanceFromStatus(
                            name = instanceName,
                            isPrimaryReported = reportedPrimary[instanceName],
                            currentPrimary = currentPrimary,
                            pvcSize = pvcSizeByInstance[instanceName].orEmpty(),
                        )
                }

            CnpgClusterDetailResponse(
                detail = clusterRes.toCnpgClusterDetail(summary, instances, backups),
            )
        }
    }

    /** Fetches the named `Cluster` CRD, or null when absent / unreadable. */
    private fun getCluster(
        client: KubernetesClient,
        clusterId: UUID,
        namespace: String,
        name: String,
    ): GenericKubernetesResource? = runCatching {
        client.genericKubernetesResources(cnpgContext("Cluster"))
            .inNamespace(namespace).withName(name).get()
    }.getOrElse {
        logCnpgListFailure("Cluster", clusterId, it)
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentPrimaryOf(cluster: GenericKubernetesResource): String =
        ((cluster.additionalProperties["status"] as? Map<String, Any?>)?.get("currentPrimary") as? String).orEmpty()

    /** Instance pod names from `status.instanceNames` (the operator's authoritative roster). */
    @Suppress("UNCHECKED_CAST")
    private fun instanceNamesOf(cluster: GenericKubernetesResource): List<String> =
        ((cluster.additionalProperties["status"] as? Map<String, Any?>)?.get("instanceNames") as? List<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()

    /**
     * `instance name → isPrimary` from `status.instancesReportedState`,
     * used to label roles when the instance's pod isn't readable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun reportedPrimaryOf(cluster: GenericKubernetesResource): Map<String, Boolean> {
        val status = cluster.additionalProperties["status"] as? Map<String, Any?> ?: return emptyMap()
        val reported = status["instancesReportedState"] as? Map<String, Any?> ?: return emptyMap()
        return reported.mapNotNull { (instanceName, state) ->
            val isPrimary = (state as? Map<String, Any?>)?.get("isPrimary") as? Boolean
            if (isPrimary != null) instanceName to isPrimary else null
        }.toMap()
    }

    /** Schedule of the first ScheduledBackup targeting this cluster, keyed as `toCnpgCluster` expects. */
    @Suppress("UNCHECKED_CAST")
    private fun scheduleFor(
        client: KubernetesClient,
        clusterId: UUID,
        namespace: String,
        name: String,
    ): Map<String, String> {
        val schedule = runCatching {
            client.genericKubernetesResources(cnpgContext("ScheduledBackup"))
                .inNamespace(namespace).list().items.orEmpty()
                .firstNotNullOfOrNull { sb ->
                    val spec = sb.additionalProperties["spec"] as? Map<String, Any?> ?: return@firstNotNullOfOrNull null
                    val ref = (spec["cluster"] as? Map<String, Any?>)?.get("name") as? String
                    if (ref == name) spec["schedule"] as? String else null
                }
        }.onFailure { logCnpgListFailure("ScheduledBackup", clusterId, it) }.getOrNull()
        return if (schedule != null) mapOf("$namespace/$name" to schedule) else emptyMap()
    }

    /** Every `Backup` CRD targeting this cluster (raw, unsorted). */
    @Suppress("UNCHECKED_CAST")
    private fun listClusterBackups(
        client: KubernetesClient,
        clusterId: UUID,
        namespace: String,
        name: String,
    ): List<GenericKubernetesResource> = runCatching {
        client.genericKubernetesResources(cnpgContext("Backup"))
            .inNamespace(namespace).list().items.orEmpty()
            .filter { b ->
                val spec = b.additionalProperties["spec"] as? Map<String, Any?>
                (spec?.get("cluster") as? Map<String, Any?>)?.get("name") as? String == name
            }
    }.onFailure { logCnpgListFailure("Backup", clusterId, it) }.getOrDefault(emptyList())

    /** Most-recent successful backup timestamp, keyed as `toCnpgCluster` expects. */
    @Suppress("UNCHECKED_CAST")
    private fun lastBackupFor(
        rawBackups: List<GenericKubernetesResource>,
        namespace: String,
        name: String,
    ): Map<String, OffsetDateTime> {
        val latest = rawBackups
            .mapNotNull { b ->
                val status = b.additionalProperties["status"] as? Map<String, Any?> ?: return@mapNotNull null
                if (!(status["phase"] as? String).equals("completed", ignoreCase = true)) return@mapNotNull null
                val ts = (status["stoppedAt"] as? String) ?: (status["startedAt"] as? String) ?: return@mapNotNull null
                parseInstant(ts)
            }
            .maxOrNull()
        return if (latest != null) mapOf("$namespace/$name" to latest) else emptyMap()
    }

    /** Pods that make up the cluster, selected by the operator's `cnpg.io/cluster` label. */
    private fun listInstancePods(
        client: KubernetesClient,
        clusterId: UUID,
        namespace: String,
        name: String,
    ): List<Pod> = runCatching {
        client.pods().inNamespace(namespace).withLabel("cnpg.io/cluster", name).list().items.orEmpty()
    }.onFailure { log.warn("fabric8 cnpg pods list failed for cluster {}: {}", clusterId, it.message) }
        .getOrDefault(emptyList())

    /** `node name → availability zone` from the standard topology label. */
    private fun zoneByNode(client: KubernetesClient, clusterId: UUID): Map<String, String> = runCatching {
        client.nodes().list().items.orEmpty().mapNotNull { node ->
            val n = node.metadata?.name ?: return@mapNotNull null
            val zone = node.metadata?.labels?.get("topology.kubernetes.io/zone") ?: return@mapNotNull null
            n to zone
        }.toMap()
    }.onFailure { log.warn("fabric8 nodes list failed for cluster {}: {}", clusterId, it.message) }
        .getOrDefault(emptyMap())

    /** `instance name → bound PVC capacity`. CNPG names each data PVC after its instance pod. */
    private fun pvcSizeByInstance(client: KubernetesClient, clusterId: UUID, namespace: String): Map<String, String> = runCatching {
        client.persistentVolumeClaims().inNamespace(namespace).list().items.orEmpty().mapNotNull { pvc ->
            val n = pvc.metadata?.name ?: return@mapNotNull null
            val size = pvc.status?.capacity?.get("storage")?.toString()
                ?: pvc.spec?.resources?.requests?.get("storage")?.toString()
                ?: return@mapNotNull null
            n to size
        }.toMap()
    }.onFailure { log.warn("fabric8 pvcs list failed for cluster {}: {}", clusterId, it.message) }
        .getOrDefault(emptyMap())

    private fun parseInstant(ts: String?): OffsetDateTime? =
        if (ts.isNullOrBlank()) null else try {
            OffsetDateTime.parse(ts)
        } catch (_: DateTimeParseException) {
            null
        }

    companion object {
        private val log = LoggerFactory.getLogger(CnpgClusterDetailRoute::class.java)
    }
}

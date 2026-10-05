package bosca.kubernetes.controller.cluster

import bosca.db.withConnectionManager
import bosca.kubernetes.repository.ClusterRepository
import bosca.serialization.UUID
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Background loop that pings every registered cluster and records the
 * result in `kubernetes.cluster`.
 *
 * On each tick (default 30s) the probe:
 *
 *  1. Pulls the list of registered clusters from Postgres.
 *  2. For each cluster, in parallel, ensures the client + informer
 *     set are warmed and tries `client.kubernetesVersion` plus a
 *     `nodes().list()` / `pods().inAnyNamespace().list()` headcount.
 *  3. On success, writes `serverVersion`, `health="OK"`, `nodes`,
 *     `pods`, and bumps `last_seen_at`. Failure counters reset.
 *  4. On failure, increments the per-cluster consecutive-failure
 *     counter and marks the cluster as `WARN` (1–2 failures) or
 *     `ERROR` (3+). The last successful counts and version stay put —
 *     we never wipe known-good telemetry on a transient blip.
 *
 * Observed-state writes deliberately do not bump the row `version`
 * column (see [ClusterRepository] doc) so the probe doesn't race the
 * studio's optimistic-locked edit flow.
 */
class ClusterHealthProbe(
    private val pool: ClusterClientPool,
    private val informers: ClusterInformerRegistry,
    private val clusters: ClusterRepository,
    private val intervalMillis: Long = 30_000L,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val failureCounts = ConcurrentHashMap<UUID, Int>()
    @Volatile private var loop: Job? = null

    /** Starts the probe loop. Idempotent — repeated calls are no-ops. */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            log.info("cluster health probe started (interval={}ms)", intervalMillis)
            while (isActive) {
                runCatching { probeAll() }
                    .onFailure { log.warn("probe iteration failed: {}", it.message) }
                delay(intervalMillis)
            }
        }
    }

    /** Runs a single probe iteration across all registered clusters. */
    suspend fun probeAll() {
        val rows = withConnectionManager { clusters.list() }
        if (rows.isEmpty()) return
        coroutineScope {
            for (cluster in rows) {
                launch {
                    // runCatching catches Throwable (Errors too); log the full chain — Errors
                    // like NoClassDefFoundError can surface here past probeOne's Exception catch.
                    runCatching { probeOne(cluster.id) }
                        .onFailure { log.warn("probe wrapper failed for {}", cluster.id, it) }
                }
            }
        }
    }

    private suspend fun probeOne(clusterId: UUID) {
        try {
            // Warm the informer set — cheap if already warmed; meaningful
            // if this is the first time we've seen this cluster post-restart.
            // First-time warmup hits Postgres via ClusterCredentialService to
            // load the encrypted kubeconfig, so it needs a Connection in the
            // coroutine context. Don't hold the connection across the k8s
            // API call below.
            val client = withConnectionManager {
                informers.get(clusterId)
                pool.get(clusterId)
            }
            val telemetry = withContext(Dispatchers.IO) { fetchTelemetry(client) }
            failureCounts.remove(clusterId)
            withConnectionManager {
                clusters.updateObservedState(
                    id = clusterId,
                    serverVersion = telemetry.serverVersion,
                    health = "OK",
                    nodes = telemetry.nodeCount,
                    pods = telemetry.podCount,
                )
            }
        } catch (e: Exception) {
            val failures = failureCounts.compute(clusterId) { _, current -> (current ?: 0) + 1 } ?: 1
            val newHealth = if (failures >= ERROR_THRESHOLD) "ERROR" else "WARN"
            // fabric8 wraps real failures in "An error has occurred." — log `e` for cause chain.
            log.warn(
                "probe failed for cluster {} (consecutive failures={}, health={})",
                clusterId, failures, newHealth, e,
            )
            runCatching { withConnectionManager { clusters.markHealth(clusterId, newHealth) } }
                .onFailure { log.warn("failed marking health for {}: {}", clusterId, it.message) }
        }
    }

    private fun fetchTelemetry(client: KubernetesClient): ClusterTelemetry {
        val version = runCatching { client.kubernetesVersion?.gitVersion.orEmpty() }
            .getOrDefault("")
        val nodeCount = client.nodes().list().items.size
        val podCount = client.pods().inAnyNamespace().list().items.size
        return ClusterTelemetry(serverVersion = version, nodeCount = nodeCount, podCount = podCount)
    }

    override fun close() {
        loop?.cancel()
        loop = null
    }

    private data class ClusterTelemetry(
        val serverVersion: String,
        val nodeCount: Int,
        val podCount: Int,
    )

    companion object {
        private val log = LoggerFactory.getLogger(ClusterHealthProbe::class.java)
        private const val ERROR_THRESHOLD = 3
    }
}

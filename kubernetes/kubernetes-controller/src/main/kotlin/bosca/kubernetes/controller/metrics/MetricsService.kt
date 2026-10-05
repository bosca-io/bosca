package bosca.kubernetes.controller.metrics

import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pulls cpu / memory usage from the `metrics.k8s.io` API for a single
 * cluster. metrics-server is a separate add-on that not every cluster
 * installs (kind / bare-metal often skip it), so every call falls back
 * to an empty map when the API is missing rather than 500'ing the
 * whole route.
 *
 * The fabric8 `top()` DSL calls `metrics.k8s.io/v1beta1/{nodes,pods}`
 * under the hood. We translate its `Quantity` values into millicores
 * (cpu) and bytes (memory) so downstream mappers can render
 * percentages, MiB strings, etc. without each one re-parsing the
 * fabric8 `Quantity` format.
 */
class MetricsService(private val client: KubernetesClient) {

    /**
     * Map of node name → live cpu + memory usage. Empty when metrics-
     * server is not installed (or the API server returns 404 / 503 for
     * any other reason).
     */
    fun nodeMetrics(): Map<String, NodeUsage> = runCatching {
        client.top().nodes().metrics().items.associate { item ->
            val name = item.metadata?.name.orEmpty()
            val usage = item.usage.orEmpty()
            name to NodeUsage(
                cpuMillicores = parseCpuMillicores(usage["cpu"]),
                memoryBytes = parseMemoryBytes(usage["memory"]),
            )
        }
    }.getOrElse { e ->
        logIfNotMissingApi("nodes", e)
        emptyMap()
    }

    /**
     * Map of `"namespace/pod"` → aggregated container-level usage.
     * Aggregation is over all containers in the pod because the
     * studio's pod table renders a single cpu / memory number per row.
     */
    fun podMetrics(namespace: String? = null): Map<String, PodUsage> = runCatching {
        val list = if (namespace != null) {
            client.top().pods().metrics(namespace)
        } else {
            client.top().pods().metrics()
        }
        list.items.associate { item ->
            val ns = item.metadata?.namespace.orEmpty()
            val name = item.metadata?.name.orEmpty()
            val containers = item.containers.orEmpty()
            val cpu = containers.sumOf { parseCpuMillicores(it.usage?.get("cpu")) }
            val mem = containers.sumOf { parseMemoryBytes(it.usage?.get("memory")) }
            "$ns/$name" to PodUsage(cpuMillicores = cpu, memoryBytes = mem)
        }
    }.getOrElse { e ->
        logIfNotMissingApi("pods", e)
        emptyMap()
    }

    /**
     * Distinguishes "metrics-server is just not installed" (404 or a
     * NoClassDef-style failure on the discovery path) from a real
     * failure. The studio renders the absence as zero metrics which is
     * fine, but we still want a warn log if the metrics API exists and
     * is failing for some other reason.
     *
     * The 404 / 503 cases used to be `debug`-only — that's the right
     * level on a healthy cluster, but it silently hides "your cluster
     * has no metrics-server installed, that's why CPU and memory are
     * always zero" from operators looking at the logs. We log at WARN
     * *once per cluster scope* via [warnedScopes] so the diagnostic
     * shows up exactly once instead of every request.
     */
    private fun logIfNotMissingApi(scope: String, e: Throwable) {
        val scopeKey = "${System.identityHashCode(client)}/$scope"
        val firstSighting = warnedScopes.add(scopeKey)
        when {
            e is KubernetesClientException && e.code == 404 -> {
                if (firstSighting) {
                    log.warn(
                        "metrics.k8s.io {} returned 404 — metrics-server is not installed in this cluster. CPU and memory will report 0 until it is installed.",
                        scope,
                    )
                } else {
                    log.debug("metrics.k8s.io {} 404 (already warned)", scope)
                }
            }
            e is KubernetesClientException && e.code == 503 ->
                log.debug("metrics.k8s.io {} unavailable (503 — metrics-server starting / not ready)", scope)
            else ->
                log.warn("metrics.k8s.io {} fetch failed: {}", scope, e.message)
        }
    }

    /** Live usage snapshot for a single node. */
    data class NodeUsage(val cpuMillicores: Long, val memoryBytes: Long)

    /** Live usage snapshot for a single pod (sum across containers). */
    data class PodUsage(val cpuMillicores: Long, val memoryBytes: Long)

    companion object {
        private val log = LoggerFactory.getLogger(MetricsService::class.java)

        /**
         * Converts a fabric8 [Quantity] (`"100m"`, `"2"`, `"500000000n"`,
         * …) into integer millicores. fabric8 returns CPU usage either
         * in cores (`"2"`), millicores (`"500m"`), or nanocores
         * (`"123456789n"`); we collapse all three into millicores so
         * the mapper layer only deals with one unit.
         */
        internal fun parseCpuMillicores(quantity: Quantity?): Long {
            if (quantity == null) return 0
            val raw = quantity.amount ?: return 0
            val format = quantity.format.orEmpty()
            // metrics-server reports cpu in nanocores via the `n` suffix
            // (format = "n"). Without a suffix the value is in cores.
            return when (format) {
                "n" -> BigDecimal(raw).divide(MILLION, 0, RoundingMode.HALF_UP).toLong()
                "u" -> BigDecimal(raw).divide(THOUSAND, 0, RoundingMode.HALF_UP).toLong()
                "m" -> BigDecimal(raw).toLong()
                "" -> BigDecimal(raw).multiply(THOUSAND).toLong()
                else -> {
                    // Fall back to fabric8's canonical-bytes conversion
                    // for any unusual format — multiplied by 1000 to
                    // convert cores → millicores.
                    Quantity.getAmountInBytes(quantity)
                        .multiply(THOUSAND).toLong()
                }
            }
        }

        /**
         * Converts a memory [Quantity] (`"123Ki"`, `"1Gi"`, `"500Mi"`,
         * …) into bytes. fabric8's `Quantity.getAmountInBytes` handles
         * every standard suffix — we just narrow the BigDecimal to a
         * `Long` since a single pod's memory comfortably fits in 63
         * bits.
         */
        internal fun parseMemoryBytes(quantity: Quantity?): Long {
            if (quantity == null) return 0
            return runCatching {
                Quantity.getAmountInBytes(quantity).toLong()
            }.getOrElse { 0 }
        }

        private val THOUSAND: BigDecimal = BigDecimal(1_000)
        private val MILLION: BigDecimal = BigDecimal(1_000_000)

        /**
         * Cluster-client identity + scope ("nodes" / "pods") of a
         * 404 we've already loudly logged. Keeps the WARN to once
         * per cluster per scope so the diagnostic is findable
         * without spamming a request-rate volume of warnings.
         */
        private val warnedScopes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}

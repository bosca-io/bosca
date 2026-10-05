package bosca.kubernetes.controller.cluster

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared-index informers for one cluster, aggregated into per-namespace
 * counts.
 *
 * The set owns informers for the three resource families the studio's
 * namespaces panel summarises: pods, services, and the six workload
 * kinds (deployments, statefulsets, daemonsets, replicasets, jobs,
 * cronjobs). Each informer notifies a handler that maintains a
 * `namespace → set-of-uids` map; serving counts is a constant-time
 * map lookup plus a `set.size`.
 *
 * Why UID sets vs. counters: informer resyncs replay every observed
 * object as an add. A naïve counter would double-count on every
 * resync. Storing UIDs makes adds idempotent without us having to
 * track the resync boundary.
 *
 * Lifecycle: built and started by [ClusterInformerRegistry] on first
 * read of a cluster; stopped via [close] when the registry tears it
 * down (either on cluster invalidation or on JVM shutdown).
 */
class ClusterInformerSet(
    private val client: KubernetesClient,
) : AutoCloseable {

    private val pods = ConcurrentHashMap<String, MutableSet<String>>()
    private val services = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * One namespace → uid-set bucket for all workload kinds. UIDs are
     * cluster-unique so colliding across kinds is not a concern; using
     * a single bucket lets `counts()` return the aggregate workload
     * count without summing per-kind buckets at read time.
     */
    private val workloads = ConcurrentHashMap<String, MutableSet<String>>()

    private val informers = mutableListOf<SharedIndexInformer<*>>()

    /**
     * Starts every informer with the configured resync period and
     * registers the handler that maintains the per-namespace UID sets.
     * Idempotent on repeated invocation in the sense that the second
     * call is a programming error caught by the registry, not a
     * data-correctness issue.
     */
    fun start() {
        informers += client.pods().inAnyNamespace().inform(counterHandler(pods), RESYNC_MILLIS)
        informers += client.services().inAnyNamespace().inform(counterHandler(services), RESYNC_MILLIS)
        informers += client.apps().deployments().inAnyNamespace().inform(counterHandler(workloads), RESYNC_MILLIS)
        informers += client.apps().statefulSets().inAnyNamespace().inform(counterHandler(workloads), RESYNC_MILLIS)
        informers += client.apps().daemonSets().inAnyNamespace().inform(counterHandler(workloads), RESYNC_MILLIS)
        informers += client.apps().replicaSets().inAnyNamespace().inform(counterHandler(workloads), RESYNC_MILLIS)
        informers += client.batch().v1().jobs().inAnyNamespace().inform(counterHandler(workloads), RESYNC_MILLIS)
        informers += client.batch().v1().cronjobs().inAnyNamespace().inform(counterHandler(workloads), RESYNC_MILLIS)
        log.info("started {} informers for cluster client", informers.size)
    }

    fun counts(namespace: String): NamespaceCounts = NamespaceCounts(
        workloads = workloads[namespace]?.size ?: 0,
        pods = pods[namespace]?.size ?: 0,
        services = services[namespace]?.size ?: 0,
    )

    override fun close() {
        for (informer in informers) {
            runCatching { informer.close() }
                .onFailure { log.warn("failed closing informer: {}", it.message) }
        }
        informers.clear()
        pods.clear()
        services.clear()
        workloads.clear()
    }

    /**
     * Builds a [ResourceEventHandler] that maintains [bucket] —
     * `namespace → set-of-uids`. Idempotent under repeated adds (so
     * resyncs are safe) and tolerant of missing metadata (skips
     * silently rather than blowing up the watch).
     */
    private fun <T : HasMetadata> counterHandler(
        bucket: ConcurrentHashMap<String, MutableSet<String>>,
    ): ResourceEventHandler<T> = object : ResourceEventHandler<T> {
        override fun onAdd(obj: T) = trackPresent(obj)
        override fun onUpdate(oldObj: T, newObj: T) = trackPresent(newObj)
        override fun onDelete(obj: T, deletedFinalStateUnknown: Boolean) = trackAbsent(obj)

        private fun trackPresent(obj: T) {
            val ns = obj.metadata?.namespace ?: return
            val uid = obj.metadata?.uid ?: return
            val set = bucket.computeIfAbsent(ns) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            set.add(uid)
        }

        private fun trackAbsent(obj: T) {
            val ns = obj.metadata?.namespace ?: return
            val uid = obj.metadata?.uid ?: return
            bucket[ns]?.remove(uid)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ClusterInformerSet::class.java)

        /**
         * fabric8 calls this both "resyncPeriod" and "refresh period".
         * 60s is the sweet spot for our use: keeps the in-memory cache
         * close enough to ground truth for an admin UI, light enough to
         * not hammer the API server with re-lists every few seconds.
         */
        private const val RESYNC_MILLIS = 60_000L
    }
}

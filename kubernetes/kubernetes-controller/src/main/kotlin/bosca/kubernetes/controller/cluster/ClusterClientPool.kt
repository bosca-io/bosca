package bosca.kubernetes.controller.cluster

import bosca.kubernetes.service.ClusterCredentialService
import bosca.serialization.UUID
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-cluster fabric8 [KubernetesClient] cache.
 *
 * The pool lazily builds a client the first time a cluster id is
 * requested: it pulls the encrypted kubeconfig via
 * [ClusterCredentialService], decrypts it, parses it through
 * fabric8's `Config.fromKubeconfig`, and caches the resulting
 * `KubernetesClient`. Subsequent lookups return the cached instance
 * directly — fabric8 clients own a long-lived OkHttp client and
 * connection pool, so reusing them is required for any non-trivial
 * read volume.
 *
 * Concurrency: the cache is a [ConcurrentHashMap] for the lock-free
 * fast path, with a [Mutex] guarding the slow-path build step so two
 * concurrent first-time lookups for the same cluster don't race to
 * construct duplicate clients.
 *
 * Invalidation: callers must call [invalidate] when a cluster's
 * kubeconfig is rotated or the cluster is removed — the wiring for
 * that signal travels through bosca-server (see
 * `KubernetesMutationsController.rotateKubeconfig` /
 * `removeCluster`) and lands here as a follow-up. Until that
 * notification path lands, restarting `kubernetes-controller` is the
 * way to pick up a rotated kubeconfig.
 */
class ClusterClientPool(
    private val credentials: ClusterCredentialService,
) : AutoCloseable {

    private val clients = ConcurrentHashMap<UUID, KubernetesClient>()
    private val buildMutex = Mutex()
    private val invalidationListeners = CopyOnWriteArrayList<(UUID) -> Unit>()

    /**
     * Registers a callback fired *after* a client has been invalidated.
     * Used by the informer registry to tear down its watches when the
     * underlying client is gone; the listener list is intentionally
     * append-only — invalidation listeners are wired at startup and
     * never need to be removed during the process lifetime.
     */
    fun onInvalidate(listener: (UUID) -> Unit) {
        invalidationListeners.add(listener)
    }

    /**
     * Returns the fabric8 client for [clusterId], building and caching
     * one on first request. Throws [IllegalStateException] when no
     * kubeconfig is stored for the cluster — the resolver layer should
     * surface this as a `Cluster not found` GraphQL error.
     */
    suspend fun get(clusterId: UUID): KubernetesClient {
        clients[clusterId]?.let { return it }
        return buildMutex.withLock {
            clients[clusterId]?.let { return@withLock it }
            val kubeconfig = credentials.load(clusterId)
                ?: error("No kubeconfig stored for cluster $clusterId")
            val config = Config.fromKubeconfig(kubeconfig)
            val client = KubernetesClientBuilder().withConfig(config).build()
            log.info("built fabric8 client for cluster {} ({})", clusterId, config.masterUrl)
            clients[clusterId] = client
            client
        }
    }

    /**
     * Drops the cached client for [clusterId] and closes the underlying
     * OkHttp/Vert.x resources. Idempotent — calling for an absent id
     * is a no-op. Call this on cluster removal and on kubeconfig
     * rotation so the next read rebuilds with fresh credentials.
     */
    fun invalidate(clusterId: UUID) {
        clients.remove(clusterId)?.let { runCatching { it.close() } }
        for (listener in invalidationListeners) {
            runCatching { listener(clusterId) }
                .onFailure { log.warn("invalidation listener failed for cluster {}: {}", clusterId, it.message) }
        }
    }

    override fun close() {
        for (client in clients.values) {
            runCatching { client.close() }
        }
        clients.clear()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ClusterClientPool::class.java)
    }
}

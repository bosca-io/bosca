package bosca.kubernetes.controller.cluster

import bosca.serialization.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the [ClusterInformerSet] for every cluster the controller has
 * observed.
 *
 * Informer sets are created lazily on first request — the studio's
 * first read of a namespace list for a cluster triggers the set
 * construction in the background; the *initial* response sees zero
 * counts and subsequent responses see warmed-up counts. This avoids
 * paying for informers on clusters nobody is looking at.
 *
 * Lifecycle: the registry subscribes to [ClusterClientPool.onInvalidate]
 * so a kubeconfig rotation or cluster removal also tears down the
 * informer set. [close] stops every set on JVM shutdown.
 */
class ClusterInformerRegistry(
    private val pool: ClusterClientPool,
) : AutoCloseable {

    private val sets = ConcurrentHashMap<UUID, ClusterInformerSet>()
    private val buildMutex = Mutex()

    init {
        pool.onInvalidate { clusterId ->
            sets.remove(clusterId)?.let { set ->
                runCatching { set.close() }
                    .onFailure { log.warn("failed closing informer set on invalidation: {}", it.message) }
            }
        }
    }

    /**
     * Returns the informer set for [clusterId], building and starting
     * one on first request. Subsequent calls reuse the live set.
     */
    suspend fun get(clusterId: UUID): ClusterInformerSet {
        sets[clusterId]?.let { return it }
        return buildMutex.withLock {
            sets[clusterId]?.let { return@withLock it }
            val client = pool.get(clusterId)
            val set = ClusterInformerSet(client)
            set.start()
            sets[clusterId] = set
            log.info("warmed informer set for cluster {}", clusterId)
            set
        }
    }

    override fun close() {
        for ((id, set) in sets) {
            runCatching { set.close() }
                .onFailure { log.warn("failed closing informer set for {}: {}", id, it.message) }
        }
        sets.clear()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ClusterInformerRegistry::class.java)
    }
}

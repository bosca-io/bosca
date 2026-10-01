package bosca.content.transition

import bosca.cache.CacheManager
import bosca.cache.nats.NatsCacheManager
import bosca.lock.DistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * Runs [CollectionTransitionEndToEndTest] scenarios using NATS JetStream for the job queue,
 * distributed locking, and cache infrastructure.
 */
class NatsCollectionTransitionEndToEndTest : CollectionTransitionEndToEndTest() {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool

    override fun startContainers() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())
        natsContainer.start()

        natsPool = natsContainer.newConnectionPool(1)
    }

    override fun stopContainers() {
        if (::natsContainer.isInitialized) natsContainer.stop()
    }

    override fun createJobQueueFactory(distributedLockFactory: DistributedLockFactory): JobQueueFactory {
        return NatsJobQueueFactory(natsPool, testJson, distributedLockFactory, null, emptyList())
    }

    override fun createDistributedLockFactory(): DistributedLockFactory {
        return NatsDistributedLockFactory(natsPool)
    }

    override fun createCacheManager(): CacheManager {
        return NatsCacheManager(natsPool)
    }
}

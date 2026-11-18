package bosca.content.metadata.jobs

import bosca.lock.redis.RedisDistributedLockFactory
import bosca.cache.CacheManager
import bosca.cache.nats.NatsCacheManager
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.test.resources.SharedValkeyContainer
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * Runs [MetadataSyncStateEndToEndTest] scenarios using NATS JetStream for the job queue,
 * and cache infrastructure, with Redis distributed locks.
 */
class NatsMetadataSyncStateEndToEndTest : MetadataSyncStateEndToEndTest() {

    private lateinit var lockRedis: SharedValkeyContainer
    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool

    override fun startContainers() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())
        natsContainer.start()
        lockRedis = SharedValkeyContainer()
        lockRedis.start()

        natsPool = natsContainer.newConnectionPool(1)
    }

    override fun stopContainers() {
        if (::lockRedis.isInitialized) lockRedis.stop()
        if (::natsContainer.isInitialized) natsContainer.stop()
    }

    override fun createJobQueueFactory(distributedLockFactory: DistributedLockFactory): JobQueueFactory {
        return NatsJobQueueFactory(natsPool, testJson, distributedLockFactory, null, emptyList())
    }

    override fun createDistributedLockFactory(): DistributedLockFactory {
        return RedisDistributedLockFactory(lockRedis.newConnectionPool())
    }

    override fun createCacheManager(): CacheManager {
        return NatsCacheManager(natsPool)
    }
}

package bosca.content.metadata.jobs

import bosca.cache.CacheManager
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.lock.DistributedLockFactory
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.redis.RedisConnectionPool
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import bosca.test.resources.SharedValkeyContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runs [MetadataSyncStateEndToEndTest] scenarios using Redis for the job queue,
 * distributed locking, and cache infrastructure.
 */
class RedisMetadataSyncStateEndToEndTest : MetadataSyncStateEndToEndTest() {

    private lateinit var redisContainer: SharedValkeyContainer
    private lateinit var redisPool: RedisConnectionPool
    private lateinit var cacheScope: CoroutineScope

    override fun startContainers() {
        cacheScope = CoroutineScope(SupervisorJob())
        redisContainer = SharedValkeyContainer()
        redisContainer.start()

        redisPool = redisContainer.newConnectionPool()
    }

    override fun stopContainers() {
        if (::cacheScope.isInitialized) cacheScope.cancel()
        if (::redisContainer.isInitialized) redisContainer.stop()
    }

    override fun createJobQueueFactory(distributedLockFactory: DistributedLockFactory): JobQueueFactory {
        return RedisJobQueueFactory(redisPool, testJson, distributedLockFactory, null, emptyList())
    }

    override fun createDistributedLockFactory(): DistributedLockFactory {
        return RedisDistributedLockFactory(redisPool)
    }

    override fun createCacheManager(): CacheManager {
        return RedisCacheManager(redisPool, RedisCacheScripts(redisPool), cacheScope)
    }
}

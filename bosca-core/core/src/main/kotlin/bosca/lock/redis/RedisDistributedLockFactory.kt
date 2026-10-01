package bosca.lock.redis

import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.redis.RedisConnectionPool
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.coroutines

class RedisDistributedLockFactory(
    private val redisConnectionPool: RedisConnectionPool
) : DistributedLockFactory {

    override suspend fun create(name: String): DistributedLock = RedisDistributedLock(redisConnectionPool, name)

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun forceRelease(name: String): Boolean {
        val key = "locks:$name"
        val connection = redisConnectionPool.connection()
        return try {
            val removed = connection.coroutines().del(key)
            removed != null && removed > 0
        } finally {
            redisConnectionPool.release(connection)
        }
    }
}
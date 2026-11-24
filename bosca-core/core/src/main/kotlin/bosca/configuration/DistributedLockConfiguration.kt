package bosca.configuration

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.lock.DistributedLockFactory
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication

@Providers
class DistributedLockConfiguration {

    @Provider
    suspend fun distributedLockFactory(
        application: BoscaApplication,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>
    ): DistributedLockFactory = when (application.environment.config.propertyOrNull("distributedLock.type")?.getString()) {
        "postgres" -> bosca.lock.postgres.PostgresDistributedLockFactory(bosca.di.provide<bosca.db.ConnectionPool>(bosca.db.PostgresInfrastructurePoolProvider.NAME))
        null, "redis" -> RedisDistributedLockFactory(redisConnectionPool.get())
        else -> error("Unsupported distributedLock.type; use redis and configure the shared Redis connection")
    }
}

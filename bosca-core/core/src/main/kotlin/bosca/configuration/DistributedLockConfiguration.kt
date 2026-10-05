package bosca.configuration

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.lock.DistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication

@Providers
class DistributedLockConfiguration {

    @Provider
    suspend fun distributedLockFactory(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>
    ): DistributedLockFactory = when (application.environment.config.propertyOrNull("distributedLock.type")?.getString()) {
        "nats" -> NatsDistributedLockFactory(natsConnectionPool.get())
        else -> RedisDistributedLockFactory(redisConnectionPool.get())
    }
}

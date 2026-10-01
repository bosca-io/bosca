package bosca.configuration

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class RedisConnectionConfig(
    val host: String,
    val port: Int,
    val database: Int = 0,
    val namespace: String = "",
)

/**
 * Registers the Redis connection pool from the application's `redis` configuration block,
 * providing distributed caching and pub/sub capabilities to the server.
 */
class RedisModule : BoscaApplicationModule {
    private val log = LoggerFactory.getLogger(RedisModule::class.java)

    @OptIn(InternalDI::class)
    override suspend fun install(application: BoscaApplication) = with(application) {
        val redis = environment.config.propertyOrNull("redis")?.getAs<RedisConnectionConfig>()
        if (redis != null) {
            val database = redis.database.takeIf { it >= 0 } ?: run {
                log.error("Ignoring invalid redis.database {}; using logical database 0", redis.database)
                0
            }
            if (database == 0 && redis.namespace.isEmpty()) {
                RedisConnectionPool.register(redis.host, redis.port)
            } else {
                if (redis.namespace.isBlank()) {
                    log.warn(
                        "redis.namespace is not set for logical database {}; Pub/Sub channels are shared " +
                            "with every other client of this Redis server",
                        database,
                    )
                }
                RedisConnectionPool.register(redis.host, redis.port, database, redis.namespace)
            }
            onShutdown {
                for (provider in ProviderRegistry.findAll(RedisConnectionPool::class)) {
                    runCatching {
                        provider.get().close()
                    }
                }
            }
        }
    }
}

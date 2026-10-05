package bosca.cache

import bosca.cache.nats.NatsCacheManager
import bosca.cache.redis.RedisCacheManager
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

/**
 * Configures the distributed cache subsystem based on the application's `cache.type`
 * configuration, selecting either Redis or NATS as the backing implementation.
 */
class CacheModule : BoscaApplicationModule {
    @OptIn(InternalDI::class)
    override suspend fun install(application: BoscaApplication) = with(application) {
        RequestCacheSerializer.register()
        when (val type = environment.config.propertyOrNull("cache.type")?.getString() ?: "redis") {
            "redis" -> {
                RedisCacheManager.register(application)
                onShutdown {
                    for (provider in ProviderRegistry.findAll(CacheManager::class)) {
                        (provider.get() as? RedisCacheManager)?.shutdown()
                    }
                }
            }
            "nats" -> NatsCacheManager.register()
            else -> error("Unknown cache type: $type")
        }
    }
}

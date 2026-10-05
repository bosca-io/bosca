package bosca.server.configuration

import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.livesessions.nats.NatsLiveSessions
import bosca.analytics.livesessions.redis.RedisLiveSessions
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import kotlinx.serialization.json.Json

/**
 * DI configuration for the analytics collector.
 *
 * The collector is intentionally a minimal GraalVM-compatible service that
 * receives analytics events over HTTP, enriches them statically, and publishes
 * them onto the pubsub transport for downstream processing by the analytics
 * processor. It does not run job queues or consume events itself.
 *
 * The [PubSubService] binding lives here (rather than being discovered via KSP)
 * because the concrete implementation is chosen at runtime from the
 * `pubsub.type` configuration property, so each server must wire its own.
 */
@Providers
class Configuration {

    /**
     * Selects the NATS or Redis [PubSubService] implementation based on the
     * `pubsub.type` configuration property. Defaults to Redis when unset so
     * the binding is still satisfied in Redis-only deployments.
     */
    @Provider
    suspend fun pubsubService(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json
    ): PubSubService {
        return when (application.environment.config.propertyOrNull("pubsub.type")?.getString()) {
            "nats" -> NatsPubSubServiceImpl(json, natsConnectionPool.get())
            else -> RedisPubSubServiceImpl(json, redisConnectionPool.get())
        }
    }

    /**
     * Selects the NATS or Redis [LiveSessionsService] implementation for the live sessions map, from the
     * `liveSessions.type` property. Defaults to the same backend as pub/sub (`pubsub.type`) when unset,
     * so a deployment does not have to configure it separately.
     */
    @Provider(singleton = true)
    suspend fun liveSessions(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json
    ): LiveSessionsService {
        val type = application.environment.config.propertyOrNull("liveSessions.type")?.getString()
            ?: application.environment.config.propertyOrNull("pubsub.type")?.getString()
        return when (type) {
            "nats" -> NatsLiveSessions(natsConnectionPool.get(), json)
            else -> RedisLiveSessions(redisConnectionPool.get(), json)
        }
    }
}

package bosca.counter

import bosca.counter.nats.NatsCounter
import bosca.counter.redis.RedisCounter
import bosca.di.provide
import bosca.di.provides
import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import kotlinx.serialization.json.Json

/**
 * Wires the distributed [Counter], selecting the backend from the application's `counter.type`
 * configuration — `redis` (default) or `nats`. Mirrors [bosca.cache.CacheModule], but keeps the
 * `provides` wiring here rather than in a per-impl `register()` companion.
 */
class CounterModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) = with(application) {
        when (val type = environment.config.propertyOrNull("counter.type")?.getString() ?: "redis") {
            "redis" -> provides<Counter>(true) { RedisCounter(provide<RedisConnectionPool>()) }
            "nats" -> provides<Counter>(true) { NatsCounter(provide<NatsConnectionPool>(), provide<Json>()) }
            else -> error("Unknown counter type: $type")
        }
    }
}

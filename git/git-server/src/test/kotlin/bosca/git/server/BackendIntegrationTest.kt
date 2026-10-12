package bosca.git.server

import bosca.cache.CacheManager
import bosca.cache.CacheModule
import bosca.cache.nats.NatsCacheManager
import bosca.cache.redis.RedisCacheManager
import bosca.cache.serializers.StringKeySerializer
import bosca.configuration.NatsModule
import bosca.configuration.RedisModule
import bosca.counter.Counter
import bosca.counter.CounterModule
import bosca.counter.nats.NatsCounter
import bosca.counter.redis.RedisCounter
import bosca.di.CoreProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provideProvider
import bosca.lock.DistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedValkeyContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(InternalDI::class)
class BackendIntegrationTest {
    @Test
    fun `packaged Git configuration wires independently selected Redis and NATS backends`(): Unit = runBlocking {
        System.setProperty("io.lettuce.core.kqueue", "false")
        System.setProperty("io.lettuce.core.epoll", "false")
        val nats = SharedNatsContainer()
        val redis = SharedValkeyContainer()
        try {
            nats.start()
            redis.start()
            val selections = listOf(
                mapOf("CACHE_TYPE" to "redis", "PUBSUB_TYPE" to "nats", "JOB_QUEUE_FACTORY" to "nats",
                    "DISTRIBUTED_LOCK_TYPE" to "redis", "COUNTER_TYPE" to "redis"),
                mapOf("CACHE_TYPE" to "nats", "PUBSUB_TYPE" to "redis", "JOB_QUEUE_FACTORY" to "redis",
                    "DISTRIBUTED_LOCK_TYPE" to "nats", "COUNTER_TYPE" to "nats"),
            )
            selections.forEachIndexed { index, backends ->
                val id = "mixed-$index"
                ProviderRegistry.clear()
                var yaml = checkNotNull(javaClass.getResourceAsStream("/application.yaml"))
                    .bufferedReader().use { it.readText() }
                    .replace("\$REDIS_HOST:localhost", redis.host)
                    .replace("\${REDIS_PORT:6380}", redis.firstMappedPort.toString())
                    .replace("\${REDIS_DATABASE:0}", redis.database.toString())
                    .replace("\$REDIS_NAMESPACE:", redis.namespace)
                    .replace("\$NATS_HOST:localhost:4222", nats.url)
                    .replace("\$NATS_USERNAME:", nats.username)
                    .replace("\$NATS_PASSWORD:", nats.password)
                backends.forEach { (setting, backend) -> yaml = yaml.replace("\$$setting:nats", backend) }
                val application = BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))
                try {
                    CoreProviderRegistrar().register()
                    application.install(RedisModule())
                    application.install(NatsModule())
                    application.install(CacheModule())
                    application.install(CounterModule())
                    withTimeout(30_000) {
                        val configuration = Configuration()
                        val natsProvider = provideProvider<NatsConnectionPool>()
                        val redisProvider = provideProvider<RedisConnectionPool>()
                        val locks = provide<DistributedLockFactory>()
                        if (backends.getValue("DISTRIBUTED_LOCK_TYPE") == "redis") {
                            assertIs<RedisDistributedLockFactory>(locks)
                        } else assertIs<NatsDistributedLockFactory>(locks)
                        val factory = configuration.jobQueueFactory(application, natsProvider, redisProvider, application.json, locks)
                        if (backends.getValue("JOB_QUEUE_FACTORY") == "redis") {
                            assertIs<RedisJobQueueFactory>(factory)
                        } else assertIs<NatsJobQueueFactory>(factory)

                        val cacheManager = provide<CacheManager>()
                        if (backends.getValue("CACHE_TYPE") == "redis") {
                            assertIs<RedisCacheManager>(cacheManager)
                        } else assertIs<NatsCacheManager>(cacheManager)
                        val cache = cacheManager.maybeAddCache(id, StringKeySerializer, 1.minutes)
                        val key = StringKeySerializer.toLocalKey(id, "key")
                        cache.put(key, "cached-$id")
                        assertEquals("cached-$id", cache.get(key).value)

                        val counter = provide<Counter>()
                        if (backends.getValue("COUNTER_TYPE") == "redis") {
                            assertIs<RedisCounter>(counter)
                        } else assertIs<NatsCounter>(counter)
                        assertEquals(1L, counter.increment("counter-$id", 1))
                        assertEquals(1L, counter.get("counter-$id"))

                        val first = locks.create("lock-$id")
                        val second = locks.create("lock-$id")
                        assertTrue(first.tryAcquire(10_000))
                        assertFalse(second.tryAcquire(10_000))
                        assertTrue(first.release())
                        assertTrue(second.tryAcquire(10_000))
                        assertTrue(second.release())

                        val pubsub = configuration.pubsubService(application, natsProvider, redisProvider, application.json)
                        if (backends.getValue("PUBSUB_TYPE") == "redis") {
                            assertIs<RedisPubSubServiceImpl>(pubsub)
                        } else assertIs<NatsPubSubServiceImpl>(pubsub)
                        coroutineScope {
                            val received = async { pubsub.subscribe("git.$id", String.serializer()).first().message }
                            val publisher = launch {
                                while (isActive) {
                                    pubsub.publish("git.$id", String.serializer(), "event-$id")
                                    delay(50)
                                }
                            }
                            try {
                                assertEquals("event-$id", received.await())
                            } finally {
                                publisher.cancelAndJoin()
                            }
                        }
                    }
                } finally {
                    application.shutdown()
                }
            }
        } finally {
            ProviderRegistry.clear()
            redis.stop()
            nats.stop()
        }
    }
}

package bosca.server.configuration

import bosca.configuration.RedisModule
import bosca.counter.Counter
import bosca.counter.CounterModule
import bosca.counter.redis.RedisCounter
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.test.resources.SharedValkeyContainer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class ProcessorRedisIntegrationTest {

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `packaged Redis configuration supports counter writes reads and expiry`(): Unit = runBlocking {
        System.setProperty("io.lettuce.core.kqueue", "false")
        System.setProperty("io.lettuce.core.epoll", "false")
        val redis = SharedValkeyContainer()
        redis.start()
        try {
            val yaml = checkNotNull(javaClass.getResourceAsStream("/application.yaml"))
                .bufferedReader().use { it.readText() }
                .replace("\$REDIS_HOST:localhost", redis.host)
                .replace("\${REDIS_PORT:6380}", redis.firstMappedPort.toString())
                .replace("\${REDIS_DATABASE:0}", redis.database.toString())
                .replace("\$REDIS_NAMESPACE:", redis.namespace)
            val application = BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))
            try {
                application.install(RedisModule())
                application.install(CounterModule())

                withTimeout(30_000) {
                    val counter = assertIs<RedisCounter>(provide<Counter>())
                    assertEquals(0L, counter.get("processor-counter"))
                    assertEquals(3L, counter.increment("processor-counter", 3))
                    assertEquals(5L, counter.increment("processor-counter", 2))
                    assertEquals(5L, counter.get("processor-counter"))
                    assertEquals(
                        mapOf("processor-counter" to 5L, "missing-counter" to 0L),
                        counter.get(listOf("processor-counter", "missing-counter")),
                    )

                    val pool = provide<RedisConnectionPool>()
                    val connection = pool.connection()
                    try {
                        assertTrue(connection.sync().ttl("processor-counter") > 0)
                    } finally {
                        pool.release(connection)
                    }
                }
            } finally {
                application.shutdown()
            }
        } finally {
            redis.stop()
        }
    }
}

package bosca.postgres

import bosca.cache.StringCacheKey
import bosca.cache.postgres.PostgresCacheManager
import bosca.cache.serializers.StringKeySerializer
import bosca.counter.postgres.PostgresCounter
import bosca.db.*
import bosca.lock.postgres.PostgresDistributedLockFactory
import bosca.pubsub.PostgresPubSubService
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours

class PostgresLockIntegrationTest {
    private lateinit var fixture: SharedPostgreSQLContainer
    private lateinit var factory: ConnectionFactory
    private lateinit var pool: ConnectionPool

    @BeforeTest fun setup() {
        fixture = SharedPostgreSQLContainer().withDatabaseName("postgres_infrastructure")
        fixture.start()
        factory = ConnectionFactoryImpl(ConnectionConfig(fixture.jdbcUrl, fixture.username, fixture.password), "postgres")
        factory.create().use { connection ->
            for (resource in listOf("V200__postgres_infrastructure.sql", "V201__postgres_cache_counters.sql", "V202__postgres_locks.sql")) {
                val sql = checkNotNull(javaClass.getResourceAsStream("/db/migrations/$resource")).bufferedReader().use { it.readText() }
                connection.createStatement().use { it.execute(sql) }
            }
        }
        pool = ConnectionPool(factory)
    }
    @AfterTest fun teardown() = runBlocking {
        pool.close(); fixture.stop()
    }

    @Test fun `infrastructure pool serves cache counter and lock while a transaction holds every main connection`(): Unit = runBlocking {
        val application = bosca.server.BoscaApplication(bosca.server.config.ApplicationConfig.load(listOf(
            "cache:", "  type: postgres",
            "database:", "  postgres:",
            "    url: \"${fixture.jdbcUrl}\"", "    user: \"${fixture.username}\"", "    password: \"${fixture.password}\"",
            "    maxConnections: 1",
        ).joinToString("\n").byteInputStream()))
        val provider = PostgresInfrastructurePoolProvider(application)
        assertTrue(provider.exists)
        val infrastructure = provider.get()
        val main = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(fixture.jdbcUrl, fixture.username, fixture.password, maxConnections = 1), "postgres"))
        assertEquals(PostgresInfrastructurePoolProvider.DEFAULT_MAX_CONNECTIONS, infrastructure.maxConnections)
        val manager = main.connection()
        try {
            withContext(manager.asCoroutineContext()) {
                manager.beginTransaction()
                manager.useStatement("select 1") { it.executeQuery().close() }
                withTimeout(2.seconds) {
                    val cache = PostgresCacheManager(infrastructure).maybeAddCache("held", StringKeySerializer, 10.seconds)
                    cache.put(StringCacheKey("held", "key"), "value")
                    assertEquals("value", cache.get(StringCacheKey("held", "key")).value)
                    assertEquals(1, PostgresCounter(infrastructure).increment("held", 1))
                    val lock = PostgresDistributedLockFactory(infrastructure).create("held")
                    assertTrue(lock.tryAcquire(1000)); assertTrue(lock.release())
                }
                manager.rollbackTransaction()
            }
        } finally { manager.release(); main.close(); infrastructure.close() }
    }
    @Test fun `expired lock cannot renew or release a new owner`() = runBlocking {
        val locks = PostgresDistributedLockFactory(pool)
        val a = locks.create("same"); val b = locks.create("same")
        assertTrue(a.tryAcquire(100)); assertFalse(b.tryAcquire(1000))
        delay(150)
        assertTrue(b.tryAcquire(1000)); assertFalse(a.renew(1000)); assertFalse(a.release())
        assertTrue(b.renew(1000)); assertTrue(b.release())
        assertTrue(a.tryAcquire(1000)); assertTrue(a.release())
    }

}

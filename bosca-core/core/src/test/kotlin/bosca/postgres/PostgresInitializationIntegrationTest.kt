@file:OptIn(bosca.di.annotation.InternalDI::class)
package bosca.postgres

import bosca.cache.CacheManager
import bosca.cache.StringCacheKey
import bosca.cache.serializers.StringKeySerializer
import bosca.counter.Counter
import bosca.db.ConnectionPool
import bosca.db.PostgresInfrastructurePoolProvider
import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.initialization.InitializeModule
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.test.resources.SharedPostgreSQLContainer
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresInitializationIntegrationTest {
    @Test fun `real initialization resolves PostgreSQL infrastructure without registering broker clients`(): Unit = runBlocking {
        val database = SharedPostgreSQLContainer().withDatabaseName("postgres_initialization"); database.start()
        ProviderRegistry.clear(); GlobalOpenTelemetry.resetForTest()
        System.setProperty("otel.traces.exporter", "none"); System.setProperty("otel.logs.exporter", "none")
        val application = BoscaApplication(ApplicationConfig.load("""
            database:
              postgres:
                url: "${database.jdbcUrl}"
                user: "${database.username}"
                password: "${database.password}"
            cache:
              type: postgres
            counter:
              type: postgres
            distributedLock:
              type: postgres
        """.trimIndent().byteInputStream()))
        try {
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { c -> c.createStatement().use { stmt ->
                for (resource in listOf("V200__postgres_infrastructure.sql", "V201__postgres_cache_counters.sql", "V202__postgres_locks.sql")) {
                    stmt.execute(checkNotNull(javaClass.getResourceAsStream("/db/migrations/$resource")).bufferedReader().use { it.readText() })
                }
            } }
            application.install(InitializeModule(enableConnectionPool = false))
            assertFalse(ProviderRegistry.getProvider<NatsConnectionPool>().exists)
            assertFalse(ProviderRegistry.getProvider<RedisConnectionPool>().exists)
            val cache = provide<CacheManager>().maybeAddCache("startup", StringKeySerializer)
            cache.put(StringCacheKey("startup", "key"), "value")
            assertEquals("value", cache.get(StringCacheKey("startup", "key")).value)
            assertEquals(1L, provide<Counter>().increment("startup"))
            val lock = provide<DistributedLockFactory>().create("startup")
            assertTrue(lock.tryAcquire(1000)); assertTrue(lock.release())
            val infrastructure = provide<ConnectionPool>(PostgresInfrastructurePoolProvider.NAME)
            assertEquals(PostgresInfrastructurePoolProvider.DEFAULT_MAX_CONNECTIONS, infrastructure.maxConnections)
            assertTrue(infrastructure.createdConnections > 0)
            assertEquals(0, provide<ConnectionPool>().createdConnections, "Infrastructure operations must not draw from the main pool")
        } finally {
            application.shutdown()
            provide<ConnectionPool>().close()
            provide<ConnectionPool>(PostgresInfrastructurePoolProvider.NAME).close()
            ProviderRegistry.clear(); GlobalOpenTelemetry.resetForTest()
            System.clearProperty("otel.traces.exporter"); System.clearProperty("otel.logs.exporter")
            database.stop()
        }
    }
}

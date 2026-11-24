package bosca.postgres

import bosca.cache.StringCacheKey
import bosca.cache.postgres.PostgresCacheManager
import bosca.cache.serializers.StringKeySerializer
import bosca.counter.postgres.PostgresCounter
import bosca.db.*
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

class PostgresCacheCounterIntegrationTest {
    private lateinit var fixture: SharedPostgreSQLContainer
    private lateinit var factory: ConnectionFactory
    private lateinit var pool: ConnectionPool

    @BeforeTest fun setup() {
        fixture = SharedPostgreSQLContainer().withDatabaseName("postgres_infrastructure")
        fixture.start()
        factory = ConnectionFactoryImpl(ConnectionConfig(fixture.jdbcUrl, fixture.username, fixture.password), "postgres")
        factory.create().use { connection ->
            for (resource in listOf("V200__postgres_infrastructure.sql", "V201__postgres_cache_counters.sql")) {
                val sql = checkNotNull(javaClass.getResourceAsStream("/db/migrations/$resource")).bufferedReader().use { it.readText() }
                connection.createStatement().use { it.execute(sql) }
            }
        }
        pool = ConnectionPool(factory)
    }
    @AfterTest fun teardown() = runBlocking {
        pool.close(); fixture.stop()
    }

    @Test fun `cache expiry negative entries atomic insert and touch preserve newer values`() = runBlocking {
        val first = PostgresCacheManager(pool).maybeAddCache("test", StringKeySerializer, 250.milliseconds)
        val second = PostgresCacheManager(pool).maybeAddCache("test", StringKeySerializer, 250.milliseconds)
        val key = StringCacheKey("test", "a")
        first.put(key, null)
        assertTrue(second.get(key).exists); assertNull(second.get(key).value)
        assertFalse(second.putIfAbsent(key, "lost"))
        first.put(key, "new")
        assertEquals("new", second.getAndTouch(key).value)
        assertEquals("new", second.remove(key)?.value)
        assertFalse(first.get(key).exists)
        assertTrue(first.putIfAbsent(key, "one"))
        delay(300)
        assertFalse(second.get(key).exists)
        assertTrue(second.putIfAbsent(key, "after expiry"))
        assertEquals("after expiry", first.get(key).value)
        first.clear(); assertFalse(second.get(key).exists)
    }
    @Test fun `concurrent counter increments do not lose updates and expired bucket restarts`() = runBlocking {
        val counter = PostgresCounter(pool, 10.seconds)
        coroutineScope { repeat(50) { launch { counter.increment("key") } } }
        assertEquals(50, counter.get("key"))
        factory.create().use { c -> c.createStatement().use { it.executeUpdate("update infrastructure.counters set expires = clock_timestamp() - interval '1 second'") } }
        assertEquals(0, counter.get("key"))
        assertEquals(2, counter.increment("key", 2))
        counter.close()
    }
    @Test fun `buffered counter additions coalesce into one flush and apply on close`() = runBlocking {
        val counter = PostgresCounter(pool, 10.seconds, flushInterval = 1.hours)
        assertEquals(3, counter.increment("mixed", 3))
        coroutineScope { repeat(50) { launch { counter.add("buffered") }; launch { counter.add("mixed", 2) } } }
        assertEquals(0, counter.get("buffered"), "Additions are buffered until the next flush")
        counter.flushPending()
        assertEquals(mapOf("buffered" to 50L, "mixed" to 103L), counter.get(listOf("buffered", "mixed")))
        counter.add("buffered", 5)
        counter.close()
        assertEquals(55, counter.get("buffered"))
    }
    @Test fun `buffered counter additions are flushed periodically`() = runBlocking {
        val counter = PostgresCounter(pool, 10.seconds, flushInterval = 100.milliseconds)
        try {
            counter.add("periodic", 7)
            withTimeout(5.seconds) { while (counter.get("periodic") != 7L) delay(50) }
        } finally { counter.close() }
    }
    @Test fun `failed counter flush restores its additions for the next attempt`() = runBlocking {
        val counter = PostgresCounter(pool, 10.seconds, flushInterval = 1.hours)
        counter.add("restored", 4)
        factory.create().use { c -> c.createStatement().use { it.execute("alter table infrastructure.counters rename to counters_unavailable") } }
        try {
            assertFailsWith<java.sql.SQLException> { counter.flushPending() }
        } finally {
            factory.create().use { c -> c.createStatement().use { it.execute("alter table infrastructure.counters_unavailable rename to counters") } }
        }
        counter.add("restored", 1)
        counter.close()
        assertEquals(5, counter.get("restored"))
    }
    @Test fun `batch cache preserves ordering duplicates negative values touch and prefix removal`(): Unit = runBlocking {
        val cache = PostgresCacheManager(pool).maybeAddCache("batch", StringKeySerializer, 2.seconds)
        val a = StringCacheKey("batch", "a"); val b = StringCacheKey("batch", "b"); val missing = StringCacheKey("batch", "missing")
        cache.putBatch(listOf(a to "old", b to null, a to "new"))
        val values = cache.getBatch(listOf(b, a, missing, a))
        assertTrue(values[0].exists); assertNull(values[0].value)
        assertEquals(listOf(null, "new", null, "new"), values.map { it.value })
        assertFalse(values[2].exists)
        assertEquals(listOf("new", "new"), cache.getBatchAndTouch(listOf(a, a)).map { it.value })
        cache.removeBatch(listOf(a), emptyList()); assertFalse(cache.get(a).exists); assertTrue(cache.get(b).exists)
        cache.removeBatch(emptyList(), listOf(b)); assertFalse(cache.get(b).exists)
    }


}

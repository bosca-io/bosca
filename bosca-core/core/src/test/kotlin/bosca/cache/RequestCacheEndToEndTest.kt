package bosca.cache

import bosca.cache.nats.NatsCacheManager
import bosca.cache.serializers.StringKeySerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.nats.NatsConnectionPool
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class RequestCacheEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var connectionPool: ConnectionPool
    private lateinit var cacheManager: CacheManager

    private val serializer = object : RequestCacheSerializer {
        override fun serialize(value: Any?): String? = value?.toString()
        override fun deserialize(value: String?): Any? = value
    }

    @BeforeTest
    fun setup(): Unit = runBlocking {
        withTimeout(5.minutes) {
            natsContainer = SharedNatsContainer()
                .withExposedPorts(4222)
                .withCommand("-js")
                .withReuse(true)
                .waitingFor(Wait.forListeningPort())

            postgresContainer = SharedPostgreSQLContainer()
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", "test")
                .withEnv("POSTGRES_PASSWORD", "test")
                .withEnv("POSTGRES_DB", "test")
                .withReuse(true)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))

            Startables.deepStart(natsContainer, postgresContainer).join()

            val factory = ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgresContainer.jdbcUrl,
                    user = postgresContainer.username,
                    password = postgresContainer.password,
                ),
                key = "test"
            )
            connectionPool = ConnectionPool(factory)

            natsPool = natsContainer.newConnectionPool(1)
            cacheManager = NatsCacheManager(natsPool)
            cacheManager.maybeAddCache("test", StringKeySerializer)
        }
    }

    @AfterTest
    fun teardown(): Unit = runBlocking {
        withTimeout(5.minutes) {
            connectionPool.close()
            natsPool.close()
            natsContainer.stop()
            postgresContainer.stop()
        }
    }

    /**
     * Simulates a full request lifecycle: a value is cached inside a transaction,
     * the transaction commits, `onCommit` fires `flush()`, and a subsequent request
     * immediately sees the value from the NATS KV store.
     */
    @Test
    fun `put inside transaction flushes to NATS on commit`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            // Request 1: put a value inside a transaction, then commit
            val cm = ConnectionManager(connectionPool)
            val rc1 = RequestCache(cacheManager, serializer)
            withContext(cm.asCoroutineContext() + rc1.asCoroutineContext()) {
                cm.beginTransaction()
                rc1.put("test", "key1", "value1")
                withContext(NonCancellable) {
                    cm.commitTransaction()
                }
            }
            withContext(NonCancellable) {
                cm.release()
            }

            // Request 2: new RequestCache should see the value from NATS
            val rc2 = RequestCache(cacheManager, serializer)
            withContext(rc2.asCoroutineContext()) {
                val result = rc2.get<String, String>("test", "key1") { error("should not need lookup") }
                assertEquals("value1", result)
            }
        }
    }

    /**
     * Verifies that a cache removal inside a transaction is flushed to NATS on commit,
     * so a subsequent request does not see the stale value.
     */
    @Test
    fun `remove inside transaction flushes to NATS on commit`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            // Seed the NATS cache
            val seed = RequestCache(cacheManager, serializer)
            withContext(seed.asCoroutineContext()) {
                seed.put("test", "key1", "stale")
                seed.flush()
            }

            // Request 1: remove inside a transaction, then commit
            val cm = ConnectionManager(connectionPool)
            val rc1 = RequestCache(cacheManager, serializer)
            withContext(cm.asCoroutineContext() + rc1.asCoroutineContext()) {
                cm.beginTransaction()
                rc1.remove("test", "key1", false)
                withContext(NonCancellable) {
                    cm.commitTransaction()
                }
            }
            withContext(NonCancellable) {
                cm.release()
            }

            // Request 2: should not see "stale", should go to lookup
            val rc2 = RequestCache(cacheManager, serializer)
            withContext(rc2.asCoroutineContext()) {
                val result = rc2.get("test", "key1") { "fresh-from-db" }
                assertEquals("fresh-from-db", result)
            }
        }
    }

    /**
     * Simulates the collection publish flow: a cached "draft" state is removed inside
     * a transaction (as happens in `setPendingStateComplete`), the transaction commits,
     * and a subsequent request fetches the new "published" state from the DB lookup
     * instead of getting the stale "draft" from NATS.
     */
    @Test
    fun `publish simulation - state change is visible after transaction commit`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            // Seed: cache "draft" state as if a previous request cached the collection
            val seed = RequestCache(cacheManager, serializer)
            withContext(seed.asCoroutineContext()) {
                seed.put("test", "collection-1", "draft")
                seed.flush()
            }

            // Publish request: remove old cached state inside a transaction
            val cm = ConnectionManager(connectionPool)
            val rc1 = RequestCache(cacheManager, serializer)
            withContext(cm.asCoroutineContext() + rc1.asCoroutineContext()) {
                cm.beginTransaction()
                // Simulate: repository.setState(id, "published") — DB is updated
                // Simulate: removeFromCache(id) — invalidate the cache
                rc1.remove("test", "collection-1", false)
                // Simulate: getById(id) — re-fetch within same request (local cache only)
                val localResult = rc1.get("test", "collection-1") { "published" }
                assertEquals("published", localResult)
                withContext(NonCancellable) {
                    cm.commitTransaction()
                }
            }
            withContext(NonCancellable) {
                cm.release()
            }

            // Next request: should see "published" from lookup, NOT "draft" from NATS
            val rc2 = RequestCache(cacheManager, serializer)
            withContext(rc2.asCoroutineContext()) {
                val result = rc2.get("test", "collection-1") { "published" }
                assertEquals("published", result)
            }
        }
    }
}

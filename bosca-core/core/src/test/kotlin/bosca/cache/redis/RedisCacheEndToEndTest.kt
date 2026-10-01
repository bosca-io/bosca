package bosca.cache.redis

import bosca.cache.StringCacheKey
import bosca.cache.serializers.StringKeySerializer
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import bosca.redis.RedisScriptExecutor
import bosca.redis.SharedConnection
import bosca.test.resources.SharedValkeyContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RedisCacheEndToEndTest {

    private lateinit var valkey: SharedValkeyContainer
    private lateinit var connections: RedisConnectionPool
    private lateinit var scripts: RedisCacheScripts

    @BeforeTest
    fun setup(): Unit = runBlocking {
        withTimeout(5.minutes) {
            valkey = SharedValkeyContainer()
                .withExposedPorts(6379)
                .withReuse(true)
                .waitingFor(Wait.forListeningPort())
            valkey.start()
            connections = valkey.newConnectionPool(4)
            scripts = RedisCacheScripts(connections)
        }
    }

    @AfterTest
    fun teardown(): Unit = runBlocking {
        withTimeout(5.minutes) {
            valkey.stop()
        }
    }

    @Test
    fun `redis cache supports single batch prefix expiration and clear operations`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val cache = RedisCache(connections, scripts, "redis-cache", 30.seconds, StringKeySerializer)
            val one = StringCacheKey("redis-cache", "one")
            val two = StringCacheKey("redis-cache", "two")
            val groupA = StringCacheKey("redis-cache", "group-a")
            val groupB = StringCacheKey("redis-cache", "group-b")
            val ellipsis = StringCacheKey("redis-cache", "when-i-feel...")

            assertEquals(-1, cache.estimatedSize)
            assertFalse(cache.get(one).exists)
            assertTrue(cache.getBatch(emptyList()).isEmpty())
            assertTrue(cache.getBatchAndTouch(emptyList()).isEmpty())

            cache.put(one, "first")
            cache.put(ellipsis, "dots")
            assertEquals("first", cache.get(one).value)
            assertEquals("dots", cache.get(ellipsis).value)
            assertEquals("first", cache.getAndTouch(one).value)
            assertTrue(cache.get(one).exists)
            assertFalse(cache.putIfAbsent(one, "unexpected"))
            assertEquals("first", cache.get(one).value)
            val initialized = StringCacheKey("redis-cache", "initialized")
            assertTrue(cache.putIfAbsent(initialized, "created"))
            assertFalse(cache.putIfAbsent(initialized, "unexpected"))
            assertEquals("created", cache.remove(initialized, false)?.value)
            assertFalse(cache.get(initialized).exists)

            cache.putBatch(emptyList())
            cache.putBatch(listOf(two to "second"))
            cache.putBatch(listOf(groupA to "a", groupB to null))
            val values = cache.getBatch(listOf(one, two, groupA, groupB))
            assertEquals(listOf("first", "second", "a", ""), values.map { it.value })
            assertTrue(values.all { it.exists })
            assertEquals(values.map { it.value }, cache.getBatchAndTouch(listOf(one, two, groupA, groupB)).map { it.value })

            assertEquals("second", cache.remove(two, false)?.value)
            assertNull(cache.remove(two, false))
            assertNull(cache.remove(groupA, true))
            assertFalse(cache.get(groupA).exists)
            assertTrue(cache.get(groupB).exists)
            assertEquals("", cache.get(groupB).value)

            cache.evictExpiredItems()
            cache.clear()
            val connection = connections.connection()
            try {
                assertEquals(0L, connection.sync().hlen("redis-cache"))
                assertEquals(0L, connection.sync().zcard("redis-cache:expirations"))
            } finally {
                connections.release(connection)
            }
            assertFalse(cache.get(one).exists)
        }
    }

    @Test
    fun `redis cache treats expired entries as absent across operations`() = runBlocking {
        withTimeout(5.minutes) {
            val name = "redis-expired-operations"
            val cache = RedisCache(connections, scripts, name, 30.seconds, StringKeySerializer)
            val read = StringCacheKey(name, "read")
            val batch = StringCacheKey(name, "batch")
            val initialize = StringCacheKey(name, "initialize")
            val remove = StringCacheKey(name, "remove")

            listOf(read, batch, initialize, remove).forEach { key ->
                scripts.put(name, "$name:evictions", StringKeySerializer, key, -1, "expired")
            }

            assertFalse(cache.get(read).exists)
            assertTrue(cache.getBatch(listOf(read, batch)).none { it.exists })
            assertTrue(cache.putIfAbsent(initialize, "replacement"))
            assertEquals("replacement", cache.get(initialize).value)
            assertNull(cache.remove(remove))
        }
    }

    @Test
    fun `redis get and touch does not revive an expired score`() = runBlocking {
        withTimeout(5.minutes) {
            val name = "redis-touch"
            val key = StringCacheKey(name, "session")
            scripts.put(name, "$name:evictions", StringKeySerializer, key, -1, "expired")
            val cache = RedisCache(connections, scripts, name, 30.seconds, StringKeySerializer)

            assertFalse(cache.getAndTouch(key).exists)
            assertFalse(cache.get(key).exists)
            assertFalse(cache.getAndTouch(StringCacheKey(name, "missing")).exists)
        }
    }

    @Test
    fun `expired touch replaces a stale near cache entry with a miss`() = runBlocking {
        withTimeout(5.minutes) {
            val name = "redis-touch-near"
            val channel = "$name:evictions"
            val key = StringCacheKey(name, "session")
            val reader = NearCacheRedisCache(
                connections,
                scripts,
                name,
                java.time.Duration.ofSeconds(30),
                StringKeySerializer,
            )
            try {
                scripts.put(name, channel, StringKeySerializer, key, 30.seconds.inWholeMilliseconds, "cached")
                assertEquals("cached", reader.get(key).value)
                RedisScriptExecutor(connections, "return redis.call('ZADD', KEYS[1], 0, ARGV[1])")
                    .execute<Long>(
                        ScriptOutputType.INTEGER,
                        arrayOf("$name:expirations"),
                        key.toRemoteKey(),
                    )

                assertFalse(reader.getAndTouch(key).exists)
                assertFalse(reader.get(key).exists)
            } finally {
                reader.close()
            }
        }
    }

    @Test
    fun `near cache touch cannot mask a newer backend value`() = runBlocking {
        withTimeout(5.minutes) {
            val name = "redis-touch-newer-put"
            val key = StringCacheKey(name, "session")
            val cache = NearCacheRedisCache(
                connections,
                scripts,
                name,
                java.time.Duration.ofSeconds(30),
                StringKeySerializer,
            )
            try {
                cache.put(key, "old")
                assertEquals("old", cache.getAndTouch(key).value)

                // Publish elsewhere to model the ordering where a newer put's invalidation has already
                // been consumed before the older touch continuation finishes its local bookkeeping.
                scripts.put(name, "unused-channel", StringKeySerializer, key, 30.seconds.inWholeMilliseconds, "new")

                assertEquals("new", cache.get(key).value)
            } finally {
                cache.close()
            }
        }
    }

    @Test
    fun `redis cache scripts expire entries and cover empty results`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val name = "redis-expiration"
            val key = StringCacheKey(name, "expired")

            scripts.put(name, "$name:evictions", StringKeySerializer, key, -1, "value")
            assertTrue(
                scripts.removePrefix(name, "$name:evictions", StringKeySerializer, StringCacheKey(name, "missing")).isEmpty()
            )
            assertEquals(listOf(key.toRemoteKey()), scripts.expiration(name, "$name:evictions"))
            assertFalse(RedisCache(connections, scripts, name, 30.seconds, StringKeySerializer).get(key).exists)
            scripts.putBatch(name, "$name:evictions", StringKeySerializer, emptyList())
        }
    }

    @Test
    fun `near cache serves local hits and stays coherent across its operations`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val cache = NearCacheRedisCache(
                connections,
                scripts,
                "near-cache",
                java.time.Duration.ofSeconds(30),
                StringKeySerializer,
            )
            try {
                val one = StringCacheKey("near-cache", "one")
                val two = StringCacheKey("near-cache", "two")
                val three = StringCacheKey("near-cache", "three")

                assertFalse(cache.get(one).exists)
                assertEquals(1, cache.estimatedSize)
                assertFalse(cache.get(one).exists)
                assertTrue(cache.getBatch(emptyList()).isEmpty())

                cache.put(one, "first")
                cache.putBatch(emptyList())
                cache.putBatch(listOf(two to "second"))
                cache.putBatch(listOf(three to null, StringCacheKey("near-cache", "four") to "fourth"))
                assertEquals(4, cache.estimatedSize)

                val local = cache.getBatch(listOf(one, two))
                assertEquals(listOf("first", "second"), local.map { it.value })
                assertFalse(cache.putIfAbsent(one, "replaced"))
                assertEquals("first", cache.get(one).value)
                val initialized = StringCacheKey("near-cache", "initialized")
                assertTrue(cache.putIfAbsent(initialized, "created"))
                assertFalse(cache.putIfAbsent(initialized, "stale"))
                assertEquals("created", cache.get(initialized).value)
                val remoteOnly = StringCacheKey("near-cache", "remote")
                scripts.put(
                    "near-cache",
                    "unused",
                    StringKeySerializer,
                    remoteOnly,
                    30.seconds.inWholeMilliseconds,
                    "remote-value",
                )
                val mixed = cache.getBatch(listOf(one, remoteOnly))
                assertEquals(listOf("first", "remote-value"), mixed.map { it.value })

                val remoteMissing = StringCacheKey("near-cache", "remote-missing")
                val mixedMissing = cache.getBatch(listOf(one, remoteMissing))
                assertEquals("first", mixedMissing[0].value)
                assertFalse(mixedMissing[1].exists)

                assertEquals("second", cache.remove(two, false)?.value)
                assertNull(cache.remove(StringCacheKey("near-cache", "missing"), false))
                assertNull(cache.remove(three, true))
                cache.evictExpiredItems()
                cache.clear()
                assertEquals(0, cache.estimatedSize)
                assertFalse(cache.get(one).exists)
            } finally {
                cache.close()
            }
        }
    }

    @Test
    fun `redis cache manager creates reuses evicts and clears named caches`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val cleanupScope = CoroutineScope(SupervisorJob())
            try {
                val manager = RedisCacheManager(connections, scripts, cleanupScope)
                val first = manager.maybeAddCache("managed", StringKeySerializer, 30.seconds)
                val reused = manager.maybeAddCache("managed", StringKeySerializer, 1.seconds)
                assertTrue(first === reused)
                assertEquals(setOf("managed"), manager.cacheNames)
                assertTrue(manager.getCache<String>("managed") === first)
                assertFailsWith<IllegalStateException> { manager.getCache<String>("missing") }

                val key = StringCacheKey("managed", "key")
                first.put(key, "value")
                assertEquals("value", first.get(key).value)
                manager.evictExpiredItems()
                manager.clearAll()
                assertFalse(first.get(key).exists)
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    @Test
    fun `redis cache manager evicts healthy caches when another cache fails`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val cleanupScope = CoroutineScope(SupervisorJob())
            val manager = RedisCacheManager(connections, scripts, cleanupScope)
            val prefix = "manager-eviction-${System.nanoTime()}"
            val names = listOf("$prefix-a", "$prefix-b")
            try {
                for (name in names) {
                    manager.maybeAddCache(name, StringKeySerializer, 30.seconds)
                    scripts.put(name, "$name:evictions", StringKeySerializer, StringCacheKey(name, "expired"), -1, "expired")
                }
                val failingName = manager.cacheNames.first()
                val healthyName = names.single { it != failingName }
                val connection = connections.connection()
                try {
                    connection.sync().del("$failingName:expirations")
                    connection.sync().set("$failingName:expirations", "wrong-type")
                } finally {
                    connections.release(connection)
                }

                val failure = assertFailsWith<IllegalStateException> { manager.evictExpiredItems() }
                assertTrue(failure.message.orEmpty().contains(failingName))

                val verificationConnection = connections.connection()
                try {
                    assertNull(verificationConnection.sync().hget(healthyName, StringCacheKey(healthyName, "expired").toRemoteKey()))
                    assertEquals(
                        "expired",
                        verificationConnection.sync().hget(failingName, StringCacheKey(failingName, "expired").toRemoteKey()),
                    )
                } finally {
                    connections.release(verificationConnection)
                }
            } finally {
                try {
                    manager.clearAll()
                } finally {
                    cleanupScope.cancel()
                }
            }
        }
    }

    @Test
    fun `redis distributed locks enforce ownership renewal timeout and force release`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val factory = RedisDistributedLockFactory(connections)
            val first = factory.create("integration-lock")
            val second = factory.create("integration-lock")
            assertFalse(first.isHeld)
            assertTrue(first.tryAcquire(30_000))
            assertTrue(first.isHeld)
            assertFalse(second.tryAcquire(30_000))
            assertFalse(second.renew(30_000))
            assertTrue(first.renew(30_000))
            assertFalse(second.acquire(30_000, waitTimeoutMillis = 10, retryDelayMillis = 1))
            assertNull(second.withLock(30_000, waitTimeoutMillis = 10, retryDelayMillis = 1) { "blocked" })
            assertFalse(second.release())

            assertTrue(factory.forceRelease("integration-lock"))
            assertFalse(factory.forceRelease("integration-lock"))
            assertFalse(first.release())
            assertTrue(second.acquire(30_000, waitTimeoutMillis = 10, retryDelayMillis = 1))
            assertTrue(second.release())

            val result = factory.create("with-lock").withLock(30_000) { "done" }
            assertEquals("done", result)
        }
    }

    @Test
    fun `shared redis connections reuse healthy channels and replace closed channels`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val shared = SharedConnection(connections)
            val first = shared.connection()
            assertTrue(first === shared.connection())
            first.closeAsync().await()
            val replacement = shared.connection()
            assertFalse(first === replacement)
            assertTrue(replacement === shared.connection())

            val firstPubSub = shared.pubSubConnection()
            assertTrue(firstPubSub === shared.pubSubConnection())
            firstPubSub.closeAsync().await()
            val replacementPubSub = shared.pubSubConnection()
            assertFalse(firstPubSub === replacementPubSub)
            assertTrue(replacementPubSub === shared.pubSubConnection())
        }
    }

    @Test
    fun `redis pubsub publishes filters shares subscriptions and unsubscribes`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val service = RedisPubSubServiceImpl(Json, connections)
            val first = async { service.subscribe("events", String.serializer()).first() }
            val second = async { service.subscribe("events", String.serializer()).first() }
            delay(250)

            service.publish("other", String.serializer(), "ignored")
            service.publish("events", String.serializer(), "delivered")

            assertEquals("events", first.await().channel)
            assertEquals("delivered", second.await().message)
        }
    }

    @Test
    fun `redis script executor caches reloads and reports script failures`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val executor = RedisScriptExecutor(connections, "return ARGV[1]")
            assertEquals("value", executor.execute<String>(ScriptOutputType.VALUE, emptyArray(), "value"))
            assertEquals("cached", executor.execute<String>(ScriptOutputType.VALUE, emptyArray(), "cached"))

            val connection = connections.connection()
            try {
                connection.sync().scriptFlush()
            } finally {
                connections.release(connection)
            }
            assertEquals("reloaded", executor.execute<String>(ScriptOutputType.VALUE, emptyArray(), "reloaded"))

            val executionFailure = assertFailsWith<Exception> {
                RedisScriptExecutor(connections, "return redis.error_reply('boom')")
                    .execute<String>(ScriptOutputType.VALUE, emptyArray())
            }
            assertTrue(executionFailure.message.orEmpty().contains("Redis execution failed"))

            val loadFailure = assertFailsWith<IllegalStateException> {
                RedisScriptExecutor(connections, "return (")
                    .execute<String>(ScriptOutputType.VALUE, emptyArray())
            }
            assertTrue(loadFailure.message.orEmpty().contains("Failed to load script"))
        }
    }

    @Test
    fun `scripts without eviction publishing maintain entries and publish nothing`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val name = "redis-silent-${System.nanoTime()}"
            val channel = "$name:evictions"
            val silent = RedisCacheScripts(connections, publishEvictions = false)
            assertFalse(silent.publishEvictions)
            // Pub/Sub is server-wide, so a plain client on a unique channel sees exactly what the scripts publish.
            val client = RedisClient.create(RedisURI.Builder.redis(valkey.host, valkey.firstMappedPort).build())
            val subscriber = client.connectPubSub()
            val received = LinkedBlockingQueue<String>()
            subscriber.addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    received.add(message)
                }
            })
            try {
                subscriber.sync().subscribe(channel)
                val ttl = 30.seconds.inWholeMilliseconds
                val kept = StringCacheKey(name, "kept")
                silent.put(name, channel, StringKeySerializer, kept, ttl, "value")
                silent.putBatch(
                    name,
                    channel,
                    StringKeySerializer,
                    listOf(Triple(StringCacheKey(name, "group:a"), ttl, "a"), Triple(StringCacheKey(name, "group:b"), ttl, "b")),
                )
                assertTrue(silent.putIfAbsent(name, channel, StringKeySerializer, StringCacheKey(name, "absent"), "new", ttl))
                silent.put(name, channel, StringKeySerializer, StringCacheKey(name, "expired"), -1, "stale")
                assertEquals(
                    listOf(true to "value", false to null),
                    silent.getBatch(name, channel, StringKeySerializer, listOf(kept, StringCacheKey(name, "expired"))),
                )
                assertEquals(listOf(true to "value"), silent.getAndTouchBatch(name, channel, StringKeySerializer, listOf(kept), ttl))
                assertEquals(2, silent.removePrefix(name, channel, StringKeySerializer, StringCacheKey(name, "group")).size)
                assertEquals("value", silent.remove(name, channel, StringKeySerializer, kept))
                silent.put(name, channel, StringKeySerializer, StringCacheKey(name, "sweep"), -1, "stale")
                assertEquals(listOf(StringCacheKey(name, "sweep").toRemoteKey()), silent.expiration(name, channel))
                silent.clear(name, channel, "all")

                // Publishes on one channel arrive in execution order, so if the sentinel is first, nothing came before.
                val sentinel = StringCacheKey(name, "sentinel")
                scripts.put(name, channel, StringKeySerializer, sentinel, ttl, "published")
                assertEquals(sentinel.toRemoteKey(), received.poll(30, TimeUnit.SECONDS))
                assertTrue(received.isEmpty())
            } finally {
                subscriber.close()
                client.shutdown()
            }
        }
    }

    @Test
    fun `near cache requires scripts that publish evictions`() {
        val silent = RedisCacheScripts(connections, publishEvictions = false)
        val failure = assertFailsWith<IllegalArgumentException> {
            NearCacheRedisCache(connections, silent, "redis-near-silent", java.time.Duration.ofSeconds(30), StringKeySerializer)
        }
        assertTrue(failure.message.orEmpty().contains("publish evictions"))
    }

    @Test
    fun `redis batch removal applies exact keys and prefixes in one call`(): Unit = runBlocking {
        withTimeout(5.minutes) {
            val name = "redis-remove-batch-${System.nanoTime()}"
            val cache = RedisCache(connections, scripts, name, 30.seconds, StringKeySerializer)
            listOf("group:1", "group:2", "exact", "also:1", "kept").forEach {
                cache.put(StringCacheKey(name, it), "value-$it")
            }

            // "group:1" is covered by both an exact key and a prefix and is counted once.
            val removed = scripts.removeBatch(
                name,
                "$name:evictions",
                StringKeySerializer,
                listOf(StringCacheKey(name, "exact"), StringCacheKey(name, "group:1"), StringCacheKey(name, "missing")),
                listOf(StringCacheKey(name, "group"), StringCacheKey(name, "also")),
            )

            assertEquals(5, removed)
            listOf("group:1", "group:2", "exact", "also:1").forEach { assertFalse(cache.get(StringCacheKey(name, it)).exists) }
            assertEquals("value-kept", cache.get(StringCacheKey(name, "kept")).value)
            assertEquals(0, scripts.removeBatch(name, "$name:evictions", StringKeySerializer, emptyList(), emptyList()))

            cache.put(StringCacheKey(name, "via-cache:1"), "value")
            cache.removeBatch(emptyList(), listOf(StringCacheKey(name, "via-cache")))
            assertFalse(cache.get(StringCacheKey(name, "via-cache:1")).exists)
        }
    }
}

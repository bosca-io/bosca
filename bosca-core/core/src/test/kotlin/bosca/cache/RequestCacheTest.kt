package bosca.cache

import bosca.cache.serializers.StringKeySerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.graphql.Batch
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class RequestCacheTest {

    private class SimpleCacheValue(
        override val value: String?,
        override val exists: Boolean
    ) : CacheValue

    private class InMemoryCache : Cache<String> {

        override val keySerializer: CacheKeySerializer<String> = StringKeySerializer

        private val store = mutableMapOf<String, String?>()

        /** Remote reads served, for asserting that a value came from the request-local tier. */
        var reads = 0
            private set

        /** Remote keys written, in order. */
        val writtenKeys = mutableListOf<String>()

        override suspend fun get(key: CacheKey<String>): CacheValue {
            reads++
            val k = key.toRemoteKey()
            return if (store.containsKey(k)) SimpleCacheValue(store[k], true)
            else SimpleCacheValue(null, false)
        }

        override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> = keys.map { get(it) }

        override suspend fun put(key: CacheKey<String>, value: String?) {
            writtenKeys += key.toRemoteKey()
            store[key.toRemoteKey()] = value
        }

        override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
            entries.forEach { (key, value) -> put(key, value) }
        }

        override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
            val k = key.toRemoteKey()
            if (keyPrefix) {
                val prefix = key.toRemoteKeyPrefix()
                store.keys.filter { it.startsWith(prefix) }.forEach { store.remove(it) }
                return null
            }
            val old = store.remove(k)
            return if (old != null) SimpleCacheValue(old, true) else null
        }

        override suspend fun clear() = store.clear()

        override suspend fun evictExpiredItems() {}

        override val estimatedSize: Long get() = store.size.toLong()

        fun containsKey(key: String): Boolean = store.containsKey(StringCacheKey("test", key).toRemoteKey())

        fun getValue(key: String): String? = store[StringCacheKey("test", key).toRemoteKey()]
    }

    private class InMemoryCacheManager(private val cache: InMemoryCache) : CacheManager {

        override val cacheNames: Set<String> = setOf("test")

        override suspend fun <K> maybeAddCache(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> {
            @Suppress("UNCHECKED_CAST")
            return cache as Cache<K>
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <K> getCache(name: String): Cache<K> = cache as Cache<K>

        override suspend fun evictExpiredItems() {}

        override suspend fun clearAll() = cache.clear()
    }

    private val serializer = object : RequestCacheSerializer {
        override fun serialize(value: Any?): String? = value?.toString()
        override fun deserialize(value: String?): Any? = value
    }

    @Test
    fun `flush persists put to remote cache synchronously`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        val rc = RequestCache(manager, serializer)
        withContext(rc.asCoroutineContext()) {
            rc.put("test", "key1", "value1")
            rc.flush()
        }
        assertEquals("value1", remoteCache.getValue("key1"))
    }

    @Test
    fun `flush persists remove to remote cache synchronously`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)

        // Seed remote cache
        val rc1 = RequestCache(manager, serializer)
        withContext(rc1.asCoroutineContext()) {
            rc1.put("test", "key1", "value1")
            rc1.flush()
        }
        assertEquals("value1", remoteCache.getValue("key1"))

        // Remove and flush
        val rc2 = RequestCache(manager, serializer)
        withContext(rc2.asCoroutineContext()) {
            rc2.remove("test", "key1", false)
            rc2.flush()
        }
        assertNull(remoteCache.getValue("key1"))
    }

    @Test
    fun `second request cache sees updated remote after first flushes`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)

        // Request 1: put and flush
        val rc1 = RequestCache(manager, serializer)
        withContext(rc1.asCoroutineContext()) {
            rc1.put("test", "key1", "value1")
            rc1.flush()
        }

        // Request 2: should see value from remote cache immediately
        val rc2 = RequestCache(manager, serializer)
        withContext(rc2.asCoroutineContext()) {
            val result = rc2.get<String, String>("test", "key1") { error("should not need lookup") }
            assertEquals("value1", result)
        }
    }

    @Test
    fun `second request cache sees removal from remote after first flushes`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)

        // Request 1: put and flush
        val rc1 = RequestCache(manager, serializer)
        withContext(rc1.asCoroutineContext()) {
            rc1.put("test", "key1", "value1")
            rc1.flush()
        }

        // Request 2: remove and flush
        val rc2 = RequestCache(manager, serializer)
        withContext(rc2.asCoroutineContext()) {
            rc2.remove("test", "key1", false)
            rc2.flush()
        }

        // Request 3: should NOT see the old value — remote was cleared synchronously
        val rc3 = RequestCache(manager, serializer)
        withContext(rc3.asCoroutineContext()) {
            val result = rc3.get("test", "key1") { "fresh-from-db" }
            assertEquals("fresh-from-db", result)
        }
    }

    @Test
    fun `remove then get on same request cache returns fresh value from lookup`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)

        val rc = RequestCache(manager, serializer)
        withContext(rc.asCoroutineContext()) {
            rc.put("test", "key1", "old")
            rc.remove("test", "key1", false)
            val result = rc.get("test", "key1") { "new" }
            assertEquals("new", result)
        }
    }

    @Test
    fun `flush with no pending operations is a no-op`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        val rc = RequestCache(manager, serializer)
        withContext(rc.asCoroutineContext()) {
            rc.flush()
        }
        assertEquals(0L, remoteCache.estimatedSize)
    }

    @Test
    fun `put then remove leaves no entry in remote after flush`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        val rc = RequestCache(manager, serializer)
        withContext(rc.asCoroutineContext()) {
            rc.put("test", "key1", "value1")
            rc.remove("test", "key1", false)
            rc.flush()
        }
        assertNull(remoteCache.getValue("key1"))
    }

    @Test
    fun `get distinguishes remote hits negative entries misses and local hits`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        remoteCache.put(StringCacheKey("test", "hit"), "remote")
        remoteCache.put(StringCacheKey("test", "negative"), "")
        val rc = RequestCache(manager, serializer)
        var lookups = 0

        withContext(rc.asCoroutineContext()) {
            assertEquals("remote", rc.get<String, String>("test", "hit") { error("remote hit") })
            assertNull(rc.get<String, String>("test", "negative") { error("negative hit") })
            assertEquals("fresh", rc.get("test", "missing") { lookups++; "fresh" })
            assertEquals("fresh", rc.get<String, String>("test", "missing") { error("local hit") })
            assertNull(rc.get<String, String>("test", "null-missing") { lookups++; null })
            rc.flush()
        }

        assertEquals(2, lookups)
        assertEquals("fresh", remoteCache.getValue("missing"))
        assertTrue(remoteCache.containsKey("null-missing"))
    }

    @Test
    fun `batch combines local remote negative and resolved values`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        remoteCache.put(StringCacheKey("test", "remote"), "R")
        remoteCache.put(StringCacheKey("test", "negative"), "")
        val rc = RequestCache(manager, serializer)

        withContext(rc.asCoroutineContext()) {
            rc.put("test", "local", "L")
            rc.get<String, String>("test", "local-null") { null }

            val batch = Batch<String, String>(listOf("local", "local-null", "remote", "negative", "missing"))
            rc.getBatch("test", batch) { keys, target ->
                assertEquals(listOf("missing"), keys)
                target.setData("missing", "M")
            }
            assertEquals(listOf("L", null, "R", null, "M"), batch.getResults())
            rc.flush()
        }

        assertEquals("M", remoteCache.getValue("missing"))
    }

    @Test
    fun `batch of entirely local values avoids resolver and remote fetch`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        val rc = RequestCache(manager, serializer)
        withContext(rc.asCoroutineContext()) {
            rc.put("test", "one", "1")
            rc.put("test", "two", "2")
            val batch = Batch<String, String>(listOf("one", "two"))
            rc.getBatch("test", batch) { _, _ -> error("resolver should not run") }
            assertEquals(listOf("1", "2"), batch.getResults())
        }
    }

    @Test
    fun `prefix removal hides matching local and remote entries until fresh values resolve`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        remoteCache.put(StringCacheKey("test", "group:one"), "remote-one")
        remoteCache.put(StringCacheKey("test", "other"), "remote-other")
        val rc = RequestCache(manager, serializer)

        withContext(rc.asCoroutineContext()) {
            rc.put("test", "group:two", "local-two")
            rc.remove("test", "group", keyPrefix = true)

            assertEquals("fresh-one", rc.get("test", "group:one") { "fresh-one" })
            assertEquals("fresh-two", rc.get("test", "group:two") { "fresh-two" })
            assertEquals("remote-other", rc.get<String, String>("test", "other") { error("unrelated remote hit") })

            val batch = Batch<String, String>(listOf("group:three", "other"))
            rc.getBatch("test", batch) { keys, target ->
                assertEquals(listOf("group:three"), keys)
                target.setData("group:three", "fresh-three")
            }
            assertEquals(listOf("fresh-three", "remote-other"), batch.getResults())
            rc.flush()
        }

        assertEquals("fresh-one", remoteCache.getValue("group:one"))
        assertEquals("fresh-two", remoteCache.getValue("group:two"))
        assertEquals("fresh-three", remoteCache.getValue("group:three"))
        assertEquals("remote-other", remoteCache.getValue("other"))
    }

    @Test
    fun `remote null cache values are negative hits for get and batch`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        remoteCache.put(StringCacheKey("test", "null-get"), null)
        remoteCache.put(StringCacheKey("test", "null-batch"), null)
        val rc = RequestCache(manager, serializer)

        withContext(rc.asCoroutineContext()) {
            assertNull(rc.get<String, String>("test", "null-get") { error("null is a cached hit") })
            val batch = Batch<String, String>(listOf("null-batch"))
            rc.getBatch("test", batch) { _, _ -> error("null is a cached batch hit") }
            assertEquals(listOf(null), batch.getResults())
        }
    }

    @Test
    fun `transaction commit flushes pending writes before connection release`() = runTest {
        val remoteCache = InMemoryCache()
        val cacheManager = InMemoryCacheManager(remoteCache)
        val rc = RequestCache(cacheManager, serializer)
        val rawConnection = mockk<java.sql.Connection>(relaxed = true) {
            io.mockk.every { isClosed } returns false
            io.mockk.every { isValid(any()) } returns true
            io.mockk.every { autoCommit } returns true
        }
        val factory = mockk<bosca.db.ConnectionFactory>(relaxed = true) {
            io.mockk.every { key } returns "request-cache-commit"
            io.mockk.every { maxConnections } returns 1
            io.mockk.every { create() } returns rawConnection
        }
        val pool = ConnectionPool(factory)
        val connectionManager = ConnectionManager(pool)

        try {
            withContext(connectionManager.asCoroutineContext() + rc.asCoroutineContext()) {
                connectionManager.beginTransaction()
                rc.put("test", "committed", "value")
                assertNull(remoteCache.getValue("committed"))
                connectionManager.commitTransaction()
                assertEquals("value", remoteCache.getValue("committed"))
            }
        } finally {
            connectionManager.release()
            pool.close()
        }
    }

    @Test
    fun `connection context defers writes until release and coalesces operations`() = runTest {
        val remoteCache = InMemoryCache()
        val cacheManager = InMemoryCacheManager(remoteCache)
        val rc = RequestCache(cacheManager, serializer)
        val connectionManager = ConnectionManager(mockk<ConnectionPool>(relaxed = true))

        withContext(connectionManager.asCoroutineContext() + rc.asCoroutineContext()) {
            rc.put("test", "one", "old")
            rc.put("test", "one", "new")
            rc.remove("test", "removed", false)
            assertNull(remoteCache.getValue("one"))
        }
        connectionManager.release()
        assertEquals("new", remoteCache.getValue("one"))
    }

    @Test
    fun `pending removals distinguish exact prefix cache and nonmatching keys before commit`() = runTest {
        suspend fun resolveAfterPendingRemove(
            removeCache: String,
            removeKey: String,
            prefix: Boolean,
            targetCache: String,
            targetKey: String,
        ): String? {
            val remote = InMemoryCache()
            val rc = RequestCache(InMemoryCacheManager(remote), serializer)
            val manager = ConnectionManager(mockk<ConnectionPool>(relaxed = true))
            return try {
                withContext(manager.asCoroutineContext() + rc.asCoroutineContext()) {
                    rc.remove(removeCache, removeKey, prefix)
                    rc.get(targetCache, targetKey) { "fresh" }
                }
            } finally {
                manager.release()
            }
        }

        assertEquals("fresh", resolveAfterPendingRemove("test", "group", true, "test", "group:item"))
        assertEquals("fresh", resolveAfterPendingRemove("other", "group", true, "test", "group:item"))
        assertEquals("fresh", resolveAfterPendingRemove("test", "other", true, "test", "group:item"))
        assertEquals("fresh", resolveAfterPendingRemove("test", "other", false, "test", "group:item"))

        val remote = InMemoryCache()
        val rc = RequestCache(InMemoryCacheManager(remote), serializer)
        val manager = ConnectionManager(mockk<ConnectionPool>(relaxed = true))
        try {
            withContext(manager.asCoroutineContext() + rc.asCoroutineContext()) {
                rc.put("test", "unrelated", "local")
                rc.put("other", "group:item", "other-cache")
                rc.remove("test", "group", keyPrefix = true)
                val batch = Batch<String, String>(listOf("group:missing"))
                rc.getBatch("test", batch) { keys, target ->
                    assertEquals(listOf("group:missing"), keys)
                    target.setData("group:missing", "resolved")
                }
                assertEquals(listOf("resolved"), batch.getResults())
            }
        } finally {
            manager.release()
        }
    }

    @Test
    fun `clear operations and coroutine accessors reset state`() = runTest {
        val remoteCache = InMemoryCache()
        val manager = InMemoryCacheManager(remoteCache)
        val rc = RequestCache(manager, serializer)
        withContext(rc.asCoroutineContext()) {
            assertTrue(requestCache() === rc)
            assertTrue(currentCoroutineContext().requestCache() === rc)
            rc.put("test", "one", "1")
            rc.clearLocal()
            assertEquals("1", rc.get<String, String>("test", "one") { error("remote value should remain") })
            rc.flush()
            rc.clear("test")
        }
        assertEquals(0, remoteCache.estimatedSize)
        assertFailsWith<IllegalStateException> { requestCache() }
    }

    @Test
    fun `batch values resolved under a pending removal are not written back while remote misses are`() = runTest {
        val remoteCache = InMemoryCache()
        remoteCache.put(StringCacheKey("test", "group:one"), "stale")
        remoteCache.writtenKeys.clear()
        val rc = RequestCache(InMemoryCacheManager(remoteCache), serializer)
        val manager = ConnectionManager(mockk<ConnectionPool>(relaxed = true))
        try {
            withContext(manager.asCoroutineContext() + rc.asCoroutineContext()) {
                rc.remove("test", "group", keyPrefix = true)
                val batch = Batch<String, String>(listOf("group:one", "plain"))
                rc.getBatch("test", batch) { keys, target ->
                    assertEquals(listOf("group:one", "plain"), keys)
                    keys.forEach { target.setData(it, "fresh-$it") }
                }
                assertEquals(listOf("fresh-group:one", "fresh-plain"), batch.getResults())
            }
        } finally {
            manager.release()
        }

        assertEquals(listOf(StringCacheKey("test", "plain").toRemoteKey()), remoteCache.writtenKeys)
        assertNull(remoteCache.getValue("group:one"))
        assertEquals("fresh-plain", remoteCache.getValue("plain"))
    }

    @Test
    fun `prefix removal leaves other caches' request-local entries in place`() = runTest {
        val remoteCache = InMemoryCache()
        val rc = RequestCache(InMemoryCacheManager(remoteCache), serializer)
        val manager = ConnectionManager(mockk<ConnectionPool>(relaxed = true))
        try {
            withContext(manager.asCoroutineContext() + rc.asCoroutineContext()) {
                rc.put("test", "group:a", "test-value")
                rc.put("other", "group:a", "other-value")
                rc.remove("test", "group", keyPrefix = true)
                val readsBefore = remoteCache.reads

                assertEquals("other-value", rc.get<String, String>("other", "group:a") { error("other cache must stay local") })
                assertEquals("fresh", rc.get("test", "group:a") { "fresh" })
                assertEquals(readsBefore, remoteCache.reads)
            }
        } finally {
            manager.release()
        }
    }

    @Test
    fun `repeated prefix removals in one cache drop every match and keep the rest local`() = runTest {
        val remoteCache = InMemoryCache()
        val rc = RequestCache(InMemoryCacheManager(remoteCache), serializer)
        val manager = ConnectionManager(mockk<ConnectionPool>(relaxed = true))
        try {
            withContext(manager.asCoroutineContext() + rc.asCoroutineContext()) {
                listOf("a:1", "a:2", "b:1", "c:1").forEach { rc.put("test", it, "local-$it") }
                rc.remove("test", "a", keyPrefix = true)
                rc.remove("test", "b", keyPrefix = true)
                val readsBefore = remoteCache.reads

                assertEquals("local-c:1", rc.get<String, String>("test", "c:1") { error("unmatched entry must stay local") })
                listOf("a:1", "a:2", "b:1").forEach { key ->
                    assertEquals("fresh-$key", rc.get("test", key) { "fresh-$key" })
                }
                assertEquals(readsBefore, remoteCache.reads)
            }
        } finally {
            manager.release()
        }
        assertEquals("local-c:1", remoteCache.getValue("c:1"))
        listOf("a:1", "a:2", "b:1").forEach { assertNull(remoteCache.getValue(it)) }
    }

    private class RecordingCache(private val name: String, private val events: MutableList<String>) : Cache<String> {
        override val keySerializer: CacheKeySerializer<String> = StringKeySerializer
        override suspend fun get(key: CacheKey<String>): CacheValue = SimpleCacheValue(null, false)
        override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> = keys.map { get(it) }
        override suspend fun put(key: CacheKey<String>, value: String?) = putBatch(listOf(key to value))
        override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
            synchronized(events) { events += "$name put ${entries.map { it.first.key }}" }
        }
        override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
            synchronized(events) { events += "$name remove ${key.key} prefix=$keyPrefix" }
            return null
        }
        override suspend fun removeBatch(keys: List<CacheKey<String>>, prefixes: List<CacheKey<String>>) {
            synchronized(events) { events += "$name removeBatch ${keys.map { it.key }} ${prefixes.map { it.key }}" }
        }
        override suspend fun clear() = Unit
        override suspend fun evictExpiredItems() = Unit
        override val estimatedSize: Long get() = 0
    }

    @Test
    fun `flush sends each cache one removal batch after its writes`() = runTest {
        val events = mutableListOf<String>()
        val caches = mapOf("a" to RecordingCache("a", events), "b" to RecordingCache("b", events))
        val manager = object : CacheManager {
            override val cacheNames: Set<String> = caches.keys
            @Suppress("UNCHECKED_CAST")
            override suspend fun <K> maybeAddCache(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration) = caches.getValue(name) as Cache<K>
            @Suppress("UNCHECKED_CAST")
            override suspend fun <K> getCache(name: String) = caches.getValue(name) as Cache<K>
            override suspend fun evictExpiredItems() = Unit
            override suspend fun clearAll() = Unit
        }
        val rc = RequestCache(manager, serializer)
        val connection = ConnectionManager(mockk<ConnectionPool>(relaxed = true))
        withContext(connection.asCoroutineContext() + rc.asCoroutineContext()) {
            rc.put("a", "written", "value")
            rc.put("b", "other", "value")
            rc.remove("a", "exact-one", false)
            rc.remove("a", "exact-two", false)
            rc.remove("a", "group", keyPrefix = true)
            rc.remove("b", "exact", false)
        }
        assertTrue(events.isEmpty())
        connection.release()

        fun eventsFor(cache: String) = events.filter { it.startsWith("$cache ") }
        assertEquals(listOf("a put [written]", "a removeBatch [exact-one, exact-two] [group]"), eventsFor("a"))
        assertEquals(listOf("b put [other]", "b removeBatch [exact] []"), eventsFor("b"))
        assertTrue(events.none { " remove " in it })
    }
}

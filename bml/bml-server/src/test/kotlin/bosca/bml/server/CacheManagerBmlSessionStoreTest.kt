package bosca.bml.server

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.serializers.StringKeySerializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CacheManagerBmlSessionStoreTest {

    @Test
    fun `session handles do no I O until state is used`() = runTest {
        val manager = FakeCacheManager()
        val store = CacheManagerBmlSessionStore(manager, ttl = 7.minutes)
        val session = store.create()

        assertEquals(7.minutes, store.idleTimeout)
        assertNull(session.get("missing"))
        assertEquals(0, manager.operations("bml-sessions"))

        session.put("cart", """{"itemCount":2}""")
        assertEquals("""{"itemCount":2}""", session.get("cart"))
        assertEquals(1, manager.operations("bml-sessions"))

        val nextRequest = assertNotNull(store.get(session.id))
        assertEquals("""{"itemCount":2}""", nextRequest.get("cart"))
        assertEquals(2, manager.operations("bml-sessions"))
    }

    @Test
    fun `invalid session ids are rejected without a cache lookup`() = runTest {
        val manager = FakeCacheManager()
        val store = CacheManagerBmlSessionStore(manager)

        assertNull(store.get("not-a-session"))
        assertEquals(0, manager.operations("bml-sessions"))
    }

    @Test
    fun `put if absent initializes a state key once`() = runTest {
        val manager = FakeCacheManager()
        val store = CacheManagerBmlSessionStore(manager)
        val first = store.create()
        val second = assertNotNull(store.get(first.id))

        assertTrue(first.putIfAbsent("cart", "first"))
        assertFalse(second.putIfAbsent("cart", "second"))
        assertEquals("first", second.get("cart"))

        assertEquals(2, manager.putIfAbsentCount("bml-sessions"))
        assertEquals(1, manager.getCount("bml-sessions"))
    }

    @Test
    fun `state keys are independent cache entries`() = runTest {
        val manager = FakeCacheManager()
        val store = CacheManagerBmlSessionStore(manager)
        val session = store.create()

        repeat(200) { session.put("component:$it", "value-$it") }

        assertEquals(200, manager.putCount("bml-sessions"))
        assertEquals(0, manager.getCount("bml-sessions"))
        assertEquals(200, manager.size("bml-sessions"))
    }

    @Test
    fun `sessions remain isolated`() = runTest {
        val store = CacheManagerBmlSessionStore(FakeCacheManager())
        val a = store.create().also { it.put("cart", "a") }
        val b = store.create().also { it.put("cart", "b") }

        assertEquals("a", store.get(a.id)?.get("cart"))
        assertEquals("b", store.get(b.id)?.get("cart"))
    }

    private class FakeValue(override val value: String?, override val exists: Boolean) : CacheValue

    private class FakeCache : Cache<String> {
        private val values = HashMap<String, String?>()
        var getCount = 0
            private set
        var putCount = 0
            private set
        var putIfAbsentCount = 0
            private set

        override val keySerializer: CacheKeySerializer<String> = StringKeySerializer

        override suspend fun get(key: CacheKey<String>): CacheValue {
            getCount++
            val remote = key.toRemoteKey()
            return if (remote in values) FakeValue(values[remote], true) else FakeValue(null, false)
        }

        override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> = keys.map { get(it) }

        override suspend fun put(key: CacheKey<String>, value: String?) {
            putCount++
            values[key.toRemoteKey()] = value
        }

        override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
            entries.forEach { put(it.first, it.second) }
        }

        override suspend fun putIfAbsent(key: CacheKey<String>, value: String): Boolean {
            putIfAbsentCount++
            return values.putIfAbsent(key.toRemoteKey(), value) == null
        }

        override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
            val remote = key.toRemoteKey()
            return if (remote in values) FakeValue(values.remove(remote), true) else null
        }

        override suspend fun clear() = values.clear()
        override suspend fun evictExpiredItems() {}
        override val estimatedSize: Long get() = values.size.toLong()
        fun size(): Int = values.size
    }

    private class FakeCacheManager : CacheManager {
        private val caches = HashMap<String, Cache<*>>()
        override val cacheNames: Set<String> get() = caches.keys

        @Suppress("UNCHECKED_CAST")
        override suspend fun <K> maybeAddCache(
            name: String,
            keySerializer: CacheKeySerializer<K>,
            expiration: Duration,
        ): Cache<K> = caches.getOrPut(name) { FakeCache() } as Cache<K>

        @Suppress("UNCHECKED_CAST")
        override suspend fun <K> getCache(name: String): Cache<K> = caches.getValue(name) as Cache<K>

        fun getCount(name: String): Int = cache(name)?.getCount ?: 0
        fun putCount(name: String): Int = cache(name)?.putCount ?: 0
        fun putIfAbsentCount(name: String): Int = cache(name)?.putIfAbsentCount ?: 0
        fun operations(name: String): Int = getCount(name) + putCount(name) + putIfAbsentCount(name)
        fun size(name: String): Int = cache(name)?.size() ?: 0
        private fun cache(name: String): FakeCache? = caches[name] as? FakeCache

        override suspend fun evictExpiredItems() {}
        override suspend fun clearAll() = caches.clear()
    }
}

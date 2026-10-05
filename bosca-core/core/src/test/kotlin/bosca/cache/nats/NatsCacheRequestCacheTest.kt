package bosca.cache.nats

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.cache.serializers.StringKeySerializer
import bosca.nats.NatsConnectionPool
import io.mockk.every
import io.mockk.mockk
import io.nats.client.Connection
import io.nats.client.KeyValue
import io.nats.client.api.KeyValueEntry
import io.nats.client.api.KeyValueOperation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class NatsCacheRequestCacheTest {

    private val serializer = object : RequestCacheSerializer {
        override fun serialize(value: Any?): String? = value?.toString()
        override fun deserialize(value: String?): Any? = value
    }

    private fun mockEntry(value: String): KeyValueEntry {
        val entry = mockk<KeyValueEntry>()
        every { entry.valueAsString } returns value
        every { entry.operation } returns KeyValueOperation.PUT
        every { entry.revision } returns 1L
        return entry
    }

    private fun createNatsKv(): KeyValue {
        val store = mutableMapOf<String, String>()
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } answers {
            val key = firstArg<String>()
            store[key]?.let { mockEntry(it) }
        }
        every { kv.put(any<String>(), any<String>()) } answers {
            store[firstArg<String>()] = secondArg<String>()
            0L
        }
        every { kv.delete(any<String>()) } answers {
            store.remove(firstArg<String>())
        }
        every { kv.delete(any<String>(), 1L) } answers {
            store.remove(firstArg<String>())
        }
        every { kv.purge(any<String>()) } answers {
            store.remove(firstArg<String>())
        }
        every { kv.keys() } answers { store.keys.toList() }
        return kv
    }

    private suspend fun createCacheManager(kv: KeyValue): CacheManager {
        val connection = mockk<Connection>()
        every { connection.keyValue(any()) } returns kv
        val pool = NatsConnectionPool(connection)
        val manager = NatsCacheManager(pool)
        manager.maybeAddCache("test", StringKeySerializer)
        return manager
    }

    @Test
    fun `flush synchronously persists put through NATS cache`() = runTest {
        val kv = createNatsKv()
        val manager = createCacheManager(kv)

        val rc1 = RequestCache(manager, serializer)
        withContext(rc1.asCoroutineContext()) {
            rc1.put("test", "key1", "value1")
            rc1.flush()
        }

        // A second request cache should see the value immediately from NATS
        val rc2 = RequestCache(manager, serializer)
        withContext(rc2.asCoroutineContext()) {
            val result = rc2.get<String, String>("test", "key1") { error("should not need lookup") }
            assertEquals("value1", result)
        }
    }

    @Test
    fun `flush synchronously persists remove through NATS cache`() = runTest {
        val kv = createNatsKv()
        val manager = createCacheManager(kv)

        // Seed the cache
        val rc1 = RequestCache(manager, serializer)
        withContext(rc1.asCoroutineContext()) {
            rc1.put("test", "key1", "value1")
            rc1.flush()
        }

        // Remove and flush
        val rc2 = RequestCache(manager, serializer)
        withContext(rc2.asCoroutineContext()) {
            rc2.remove("test", "key1", false)
            rc2.flush()
        }

        // A new request cache should not see the old value
        val rc3 = RequestCache(manager, serializer)
        withContext(rc3.asCoroutineContext()) {
            val result = rc3.get("test", "key1") { "fresh-from-db" }
            assertEquals("fresh-from-db", result)
        }
    }

    @Test
    fun `state change simulation - remove and re-cache reflects new state`() = runTest {
        val kv = createNatsKv()
        val manager = createCacheManager(kv)

        // Simulate initial state being cached (like a collection with workflowState=draft)
        val rc1 = RequestCache(manager, serializer)
        withContext(rc1.asCoroutineContext()) {
            rc1.put("test", "collection-1", "draft")
            rc1.flush()
        }

        // Simulate publish: remove old cached value and flush
        val rc2 = RequestCache(manager, serializer)
        withContext(rc2.asCoroutineContext()) {
            rc2.remove("test", "collection-1", false)
            rc2.flush()
        }

        // Next request should go to DB (lookup) and get "published"
        val rc3 = RequestCache(manager, serializer)
        withContext(rc3.asCoroutineContext()) {
            val result = rc3.get("test", "collection-1") { "published" }
            assertEquals("published", result)
        }
    }
}

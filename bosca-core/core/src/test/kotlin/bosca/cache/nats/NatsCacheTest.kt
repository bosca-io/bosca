package bosca.cache.nats

import bosca.cache.CacheKey
import bosca.cache.serializers.StringKeySerializer
import bosca.nats.NatsConnectionPool
import bosca.observability.ErrorCapture
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.KeyValue
import io.nats.client.KeyValueManagement
import io.nats.client.api.KeyValueEntry
import io.nats.client.api.KeyValueOperation
import io.nats.client.api.KeyValueStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class NatsCacheTest {
    private fun entry(
        value: String,
        operation: KeyValueOperation = KeyValueOperation.PUT,
        revision: Long = 1L,
    ): KeyValueEntry =
        mockk {
            every { valueAsString } returns value
            every { this@mockk.value } returns value.toByteArray()
            every { this@mockk.operation } returns operation
            every { this@mockk.revision } returns revision
        }

    private fun key(value: String) = StringKeySerializer.toLocalKey("test:cache", value)

    @Test
    fun `get handles values deletes misses batches and failures`() = runTest {
        val kv = mockk<KeyValue>()
        val capture = mockk<ErrorCapture>(relaxed = true)
        val cache = NatsCache(kv, StringKeySerializer, capture)
        every { kv.get(match { it.endsWith("value") }) } returns entry("stored")
        every { kv.get(match { it.endsWith("deleted") }) } returns entry("old", KeyValueOperation.DELETE)
        every { kv.get(match { it.endsWith("missing") }) } returns null
        every { kv.get(match { it.endsWith("failed") }) } throws IllegalStateException("unavailable")

        val found = cache.get(key("value"))
        assertTrue(found.exists)
        assertEquals("stored", found.value)
        assertFalse(cache.get(key("deleted")).exists)
        assertFalse(cache.get(key("missing")).exists)
        assertFailsWith<IllegalStateException> { cache.get(key("failed")) }
        assertTrue(cache.getBatch(emptyList()).isEmpty())
        assertTrue(cache.getBatchAndTouch(emptyList()).isEmpty())
        assertEquals(listOf(true, false), cache.getBatch(listOf(key("value"), key("missing"))).map { it.exists })
        coVerify { capture.capture(any(), null, match { it["location"] == "NatsCache.get" }) }
    }

    @Test
    fun `get and touch renews the current revision without overwriting a concurrent update`() = runTest {
        val conflict = mockk<JetStreamApiException>()
        every { conflict.apiErrorCode } returns 10071
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } returnsMany listOf(
            entry("first", revision = 3L),
            entry("second", revision = 4L),
        )
        every { kv.update(any<String>(), "first".toByteArray(), 3L) } throws conflict
        every { kv.update(any<String>(), "second".toByteArray(), 4L) } returns 5L
        val cache = NatsCache(kv, StringKeySerializer)

        val touched = cache.getAndTouch(key("session"))

        assertTrue(touched.exists)
        assertEquals("second", touched.value)
        verify(exactly = 1) { kv.update(any<String>(), "first".toByteArray(), 3L) }
        verify(exactly = 1) { kv.update(any<String>(), "second".toByteArray(), 4L) }
    }

    @Test
    fun `get and touch accepts an identical concurrent renewal`() = runTest {
        val conflict = mockk<JetStreamApiException>()
        every { conflict.apiErrorCode } returns 10071
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } returnsMany listOf(
            entry("current", revision = 3L),
            entry("current", revision = 4L),
        )
        every { kv.update(any<String>(), "current".toByteArray(), 3L) } throws conflict
        val cache = NatsCache(kv, StringKeySerializer)

        val touched = cache.getAndTouch(key("session"))

        assertTrue(touched.exists)
        assertEquals("current", touched.value)
        verify(exactly = 1) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }
    }

    @Test
    fun `get and touch returns misses before and after a revision conflict`() = runTest {
        val conflict = mockk<JetStreamApiException>()
        every { conflict.apiErrorCode } returns 10071

        val missing = mockk<KeyValue>()
        every { missing.get(any<String>()) } returns null
        assertFalse(NatsCache(missing, StringKeySerializer).getAndTouch(key("missing")).exists)

        val deleted = mockk<KeyValue>()
        every { deleted.get(any<String>()) } returns entry("old", KeyValueOperation.DELETE)
        assertFalse(NatsCache(deleted, StringKeySerializer).getAndTouch(key("deleted")).exists)

        val removedDuringTouch = mockk<KeyValue>()
        every { removedDuringTouch.get(any<String>()) } returnsMany listOf(entry("old"), null)
        every { removedDuringTouch.update(any<String>(), any<ByteArray>(), any<Long>()) } throws conflict
        assertFalse(NatsCache(removedDuringTouch, StringKeySerializer).getAndTouch(key("removed")).exists)

        val deletedDuringTouch = mockk<KeyValue>()
        every { deletedDuringTouch.get(any<String>()) } returnsMany listOf(
            entry("old"),
            entry("old", KeyValueOperation.DELETE, revision = 2L),
        )
        every { deletedDuringTouch.update(any<String>(), any<ByteArray>(), any<Long>()) } throws conflict
        assertFalse(NatsCache(deletedDuringTouch, StringKeySerializer).getAndTouch(key("deleted-later")).exists)
    }

    @Test
    fun `get and touch propagates non-conflict backend failures`() = runTest {
        val backendFailure = JetStreamApiException(io.nats.client.api.Error.JsBadRequestErr)
        val failing = mockk<KeyValue>()
        every { failing.get(any<String>()) } returns entry("current")
        every { failing.update(any<String>(), any<ByteArray>(), any<Long>()) } throws backendFailure
        assertFailsWith<JetStreamApiException> {
            NatsCache(failing, StringKeySerializer).getAndTouch(key("failed"))
        }
    }

    @Test
    fun `get and touch bounds repeated conflicting value changes`() = runTest {
        val conflict = mockk<JetStreamApiException>()
        every { conflict.apiErrorCode } returns 10071
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } returnsMany (1L..5L).map { revision ->
            entry("value-$revision", revision = revision)
        }
        every { kv.update(any<String>(), any<ByteArray>(), any<Long>()) } throws conflict

        assertFailsWith<IllegalStateException> {
            NatsCache(kv, StringKeySerializer).getAndTouch(key("contended"))
        }
        verify(exactly = 4) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }
        verify(exactly = 5) { kv.get(any<String>()) }
    }

    @Test
    fun `five aligned readers share an identical renewal without exhausting retries`() = runTest {
        val conflict = mockk<JetStreamApiException>()
        every { conflict.apiErrorCode } returns 10071
        val initial = entry("current", revision = 3L)
        val renewed = entry("current", revision = 4L)
        val barrier = CyclicBarrier(5)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val winner = AtomicBoolean()
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } answers {
            if (reads.incrementAndGet() <= 5) {
                barrier.await(5, TimeUnit.SECONDS)
                initial
            } else {
                renewed
            }
        }
        every { kv.update(any<String>(), "current".toByteArray(), 3L) } answers {
            if (winner.compareAndSet(false, true)) 4L else throw conflict
        }
        val cache = NatsCache(kv, StringKeySerializer)

        val touched = coroutineScope {
            List(5) { async { cache.getAndTouch(key("session")) } }.awaitAll()
        }

        assertTrue(touched.all { it.exists && it.value == "current" })
        verify(exactly = 5) { kv.update(any<String>(), "current".toByteArray(), 3L) }
    }

    @Test
    fun `batch reads distinct backend keys once and preserves duplicate positions`() = runTest {
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } returns entry("current", revision = 3L)
        every { kv.update(any<String>(), "current".toByteArray(), 3L) } returns 4L
        val cache = NatsCache(kv, StringKeySerializer)
        val spaced = key("same key")
        val underscored = key("same_key")

        val values = cache.getBatchAndTouch(listOf(spaced, underscored, spaced, underscored, spaced))

        assertEquals(List(5) { "current" }, values.map { it.value })
        verify(exactly = 2) { kv.get(any<String>()) }
        verify(exactly = 2) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }
    }

    @Test
    fun `put remove prefix clear and eviction cover all mutation paths`() = runTest {
        // Batch writes run concurrently, so the fake bucket must be thread-safe.
        val store = ConcurrentHashMap(
            mapOf(
                key("prefix-one").toRemoteKey().sanitizeForNats() to "one",
                key("prefix-two").toRemoteKey().sanitizeForNats() to "two",
                "unrelated" to "three",
            )
        )
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } answers { store[firstArg()]?.let(::entry) }
        every { kv.put(any<String>(), any<String>()) } answers {
            store[firstArg()] = secondArg()
            1L
        }
        every { kv.delete(any<String>()) } answers {
            store.remove(firstArg())
        }
        every { kv.delete(any<String>(), any<Long>()) } answers {
            store.remove(firstArg())
        }
        every { kv.purge(any<String>()) } returns Unit
        every { kv.keys() } answers { store.keys.toList() }
        val cache = NatsCache(kv, StringKeySerializer)

        cache.put(key("put"), "value")
        cache.put(key("null"), null)
        cache.putBatch(listOf(key("batch") to "value", key("batch-null") to null))
        assertTrue(cache.remove(key("put"), false)?.exists == true)
        assertNull(cache.remove(key("absent"), false))
        cache.remove(key("prefix"), true)
        assertTrue(store.keys.none { it.startsWith(key("prefix").toRemoteKeyPrefix().sanitizeForNats()) })
        cache.clear()
        assertTrue(store.isEmpty())
        cache.evictExpiredItems()
    }

    @Test
    fun `batch write overlaps its NATS round trips instead of issuing them one at a time`() = runTest {
        // Each write blocks until all three have started, which only completes when they run concurrently.
        val barrier = CyclicBarrier(3)
        val kv = mockk<KeyValue>()
        every { kv.put(any<String>(), any<String>()) } answers {
            barrier.await(5, TimeUnit.SECONDS)
            1L
        }
        every { kv.delete(any<String>()) } answers { barrier.await(5, TimeUnit.SECONDS) }
        every { kv.purge(any<String>()) } returns Unit
        val cache = NatsCache(kv, StringKeySerializer)

        cache.putBatch(listOf(key("one") to "1", key("two") to "2", key("gone") to null))

        verify(exactly = 1) { kv.put(key("one").toRemoteKey().sanitizeForNats(), "1") }
        verify(exactly = 1) { kv.put(key("two").toRemoteKey().sanitizeForNats(), "2") }
        verify(exactly = 1) { kv.delete(key("gone").toRemoteKey().sanitizeForNats()) }
        verify(exactly = 1) { kv.purge(key("gone").toRemoteKey().sanitizeForNats()) }
    }

    @Test
    fun `batch write keeps only the last value for a repeated key`() = runTest {
        val kv = mockk<KeyValue>()
        every { kv.put(any<String>(), any<String>()) } returns 1L
        every { kv.delete(any<String>()) } returns Unit
        every { kv.purge(any<String>()) } returns Unit
        val cache = NatsCache(kv, StringKeySerializer)
        val repeated = key("repeated").toRemoteKey().sanitizeForNats()
        val cleared = key("cleared").toRemoteKey().sanitizeForNats()

        cache.putBatch(
            listOf(
                key("repeated") to "first",
                key("cleared") to "stale",
                key("repeated") to "last",
                key("cleared") to null,
            )
        )

        verify(exactly = 1) { kv.put(repeated, "last") }
        verify(exactly = 0) { kv.put(repeated, "first") }
        verify(exactly = 0) { kv.put(cleared, any<String>()) }
        verify(exactly = 1) { kv.delete(cleared) }
        verify(exactly = 1) { kv.purge(cleared) }
    }

    @Test
    fun `batch write propagates a failed NATS write`() = runTest {
        val kv = mockk<KeyValue>()
        every { kv.put(match { it.endsWith("ok") }, any<String>()) } returns 1L
        every { kv.put(match { it.endsWith("failed") }, any<String>()) } throws IllegalStateException("unavailable")
        val cache = NatsCache(kv, StringKeySerializer)

        val error = assertFailsWith<IllegalStateException> {
            cache.putBatch(listOf(key("ok") to "value", key("failed") to "value"))
        }

        assertEquals("unavailable", error.message)
    }

    @Test
    fun `empty batch write makes no NATS calls`() = runTest {
        val kv = mockk<KeyValue>()
        val cache = NatsCache(kv, StringKeySerializer)

        cache.putBatch(emptyList())

        verify(exactly = 0) { kv.put(any<String>(), any<String>()) }
        verify(exactly = 0) { kv.delete(any<String>()) }
    }

    @Test
    fun `batch removal lists the bucket once for every prefix and removes exact keys by revision`() = runTest {
        val groupA = key("group-a:1").toRemoteKey().sanitizeForNats()
        val groupB = key("group-b:1").toRemoteKey().sanitizeForNats()
        val unrelated = key("other").toRemoteKey().sanitizeForNats()
        val exact = key("exact").toRemoteKey().sanitizeForNats()
        val kv = mockk<KeyValue>()
        every { kv.keys() } returns listOf(groupA, groupB, unrelated)
        every { kv.delete(any<String>()) } returns Unit
        every { kv.purge(any<String>()) } returns Unit
        every { kv.get(exact) } returns entry("value", revision = 5L)
        every { kv.delete(exact, 5L) } returns Unit
        val cache = NatsCache(kv, StringKeySerializer)

        cache.removeBatch(listOf(key("exact"), key("exact")), listOf(key("group-a"), key("group-b")))

        verify(exactly = 1) { kv.keys() }
        verify(exactly = 1) { kv.delete(groupA) }
        verify(exactly = 1) { kv.purge(groupA) }
        verify(exactly = 1) { kv.delete(groupB) }
        verify(exactly = 1) { kv.purge(groupB) }
        verify(exactly = 0) { kv.delete(unrelated) }
        verify(exactly = 1) { kv.delete(exact, 5L) }
    }

    @Test
    fun `batch removal without prefixes does not list the bucket`() = runTest {
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } returns null
        val cache = NatsCache(kv, StringKeySerializer)

        cache.removeBatch(listOf(key("absent")), emptyList())
        cache.removeBatch(emptyList(), emptyList())

        verify(exactly = 0) { kv.keys() }
        verify(exactly = 1) { kv.get(any<String>()) }
    }

    @Test
    fun `prefix removal matches a composite key whose first part ends in a dot`() = runTest {
        val composite = object : CacheKey<String> {
            override val cacheName = "groups"
            override val key = "staff."
            override fun toRemoteKey(prefix: Boolean) =
                if (prefix) "gid::groups::staff." else "gid::groups::staff.::ADMIN"
        }
        val storedKey = composite.toRemoteKey().sanitizeForNats()
        val keys = mutableSetOf(storedKey)
        val kv = mockk<KeyValue>()
        every { kv.keys() } answers { keys.toList() }
        every { kv.delete(any<String>()) } answers { keys.remove(firstArg()) }
        every { kv.purge(any<String>()) } returns Unit

        NatsCache(kv, StringKeySerializer).remove(composite, keyPrefix = true)

        assertTrue(keys.isEmpty())
    }

    @Test
    fun `exact remove propagates a NATS marker read failure`() = runTest {
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } throws IllegalStateException("unavailable")
        val cache = NatsCache(kv, StringKeySerializer)

        assertFailsWith<IllegalStateException> { cache.remove(key("session")) }
        verify(exactly = 0) { kv.delete(any<String>(), any<Long>()) }
    }

    @Test
    fun `put if absent uses one NATS create operation`() = runTest {
        val conflict = mockk<JetStreamApiException>()
        every { conflict.apiErrorCode } returns 10071
        val kv = mockk<KeyValue>()
        every { kv.create(key("new").toRemoteKey().sanitizeForNats(), "value".toByteArray()) } returns 1L
        every { kv.create(key("existing").toRemoteKey().sanitizeForNats(), "value".toByteArray()) } throws conflict
        val backendFailure = JetStreamApiException(io.nats.client.api.Error.JsBadRequestErr)
        every { kv.create(key("failed").toRemoteKey().sanitizeForNats(), "value".toByteArray()) } throws backendFailure
        val cache = NatsCache(kv, StringKeySerializer)

        assertTrue(cache.putIfAbsent(key("new"), "value"))
        assertFalse(cache.putIfAbsent(key("existing"), "value"))
        assertFailsWith<JetStreamApiException> { cache.putIfAbsent(key("failed"), "value") }

        verify(exactly = 0) { kv.get(any<String>()) }
        verify(exactly = 0) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }
    }

    @Test
    fun `remove treats delete markers as missing`() = runTest {
        val deleted = mockk<KeyValue>()
        every { deleted.get(any<String>()) } returns entry("old", KeyValueOperation.DELETE)
        val deletedCache = NatsCache(deleted, StringKeySerializer)
        assertNull(deletedCache.remove(key("deleted")))
    }

    @Test
    fun `remove propagates backend failures and bounds revision contention`() = runTest {
        val backendFailure = JetStreamApiException(io.nats.client.api.Error.JsBadRequestErr)
        val failing = mockk<KeyValue>()
        every { failing.get(any<String>()) } returns entry("current")
        every { failing.delete(any<String>(), any<Long>()) } throws backendFailure
        assertFailsWith<JetStreamApiException> {
            NatsCache(failing, StringKeySerializer).remove(key("failed"))
        }

        val conflict = mockk<JetStreamApiException>()
        every { conflict.apiErrorCode } returns 10071
        val contended = mockk<KeyValue>()
        every { contended.get(any<String>()) } returns entry("current")
        every { contended.delete(any<String>(), any<Long>()) } throws conflict
        val error = assertFailsWith<IllegalStateException> {
            NatsCache(contended, StringKeySerializer).remove(key("contended"))
        }
        assertTrue("remained contended" in error.message.orEmpty())
    }

    @Test
    fun `blocking NATS cache calls run on virtual threads outside the caller dispatcher`() = runTest {
        val callerThread = Thread.currentThread()
        val invocationThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val kv = mockk<KeyValue>()
        every { kv.get(any<String>()) } answers {
            invocationThreads.add(Thread.currentThread())
            entry("current", revision = 7L)
        }
        every { kv.put(any<String>(), any<String>()) } answers {
            invocationThreads.add(Thread.currentThread())
            8L
        }
        every { kv.create(any<String>(), any<ByteArray>()) } answers {
            invocationThreads.add(Thread.currentThread())
            8L
        }
        every { kv.delete(any<String>(), 7L) } answers {
            invocationThreads.add(Thread.currentThread())
        }
        every { kv.keys() } answers {
            invocationThreads.add(Thread.currentThread())
            emptyList()
        }
        val cache = NatsCache(kv, StringKeySerializer)

        cache.get(key("value"))
        cache.put(key("value"), "new")
        assertTrue(cache.putIfAbsent(key("new"), "new"))
        assertEquals("current", cache.remove(key("value"))?.value)
        cache.clear()

        assertTrue(invocationThreads.isNotEmpty())
        assertTrue(invocationThreads.none { it === callerThread })
        assertTrue(invocationThreads.all { it.isVirtual })
    }

    @Test
    fun `estimated size and sanitizer cover success and failure`() {
        val kv = mockk<KeyValue>()
        val status = mockk<KeyValueStatus>()
        every { status.entryCount } returns 9
        every { kv.status } returns status
        val cache = NatsCache(kv, StringKeySerializer)
        assertEquals(9, cache.estimatedSize)
        every { kv.status } throws IllegalStateException("unavailable")
        assertEquals(-1, cache.estimatedSize)
        assertEquals("a_003a_003ab_003ac_0020d/e_002e=", "a::b:c d/e.=".sanitizeForNats())
    }

    @Test
    fun `new cache reuses an existing bucket or creates a missing one`() = runTest {
        val kv = mockk<KeyValue>()
        val existingConnection = mockk<Connection>()
        every { existingConnection.keyValue("test-cache") } returns kv

        NatsCache.newCache(NatsConnectionPool(existingConnection), "test:cache", 1.minutes, StringKeySerializer)

        val missingConnection = mockk<Connection>()
        val management = mockk<KeyValueManagement>(relaxed = true)
        var calls = 0
        every { missingConnection.keyValue("test-cache") } answers {
            if (calls++ == 0) throw IllegalStateException("missing") else kv
        }
        every { missingConnection.keyValueManagement() } returns management

        NatsCache.newCache(NatsConnectionPool(missingConnection), "test:cache", 1.minutes, StringKeySerializer)

        verify(exactly = 1) { management.create(any()) }
    }
}

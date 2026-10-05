package bosca.lock.nats

import io.mockk.*
import io.nats.client.JetStreamApiException
import io.nats.client.KeyValue
import io.nats.client.MessageTtl
import io.nats.client.api.KeyValueEntry
import io.nats.client.api.KeyValueOperation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NatsDistributedLockTest {

    private val kv = mockk<KeyValue>()
    private val lockName = "test-lock"
    private lateinit var lock: NatsDistributedLock

    @BeforeTest
    fun setup() {
        lock = NatsDistributedLock(kv, lockName)
    }

    @Test
    fun tryAcquireSuccess() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L

        val result = lock.tryAcquire(1000)

        assertTrue(result)
        verify { kv.create(lockName, any<ByteArray>(), any<MessageTtl>()) }
    }

    @Test
    fun tryAcquireFailureWhenKeyExists() = runTest {
        val exception = mockk<JetStreamApiException>()
        every { exception.apiErrorCode } returns 10071
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } throws exception

        val entry = mockk<KeyValueEntry>()
        every { entry.operation } returns KeyValueOperation.PUT
        every { kv.get(lockName) } returns entry

        val result = lock.tryAcquire(1000)

        assertFalse(result)
    }

    @Test
    fun tryAcquireFailsAfterDeleteTombstone() = runTest {
        val exception = mockk<JetStreamApiException>()
        every { exception.apiErrorCode } returns 10071

        every { kv.create(eq(lockName), any<ByteArray>(), any<MessageTtl>()) } throws exception

        val result = lock.tryAcquire(1000)

        assertFalse(result)
        assertFalse(lock.isHeld)
        verify(exactly = 1) { kv.create(eq(lockName), any<ByteArray>(), any<MessageTtl>()) }
        verify(exactly = 0) { kv.get(any<String>()) }
        verify(exactly = 0) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }
    }

    @Test
    fun tryAcquireFailsAfterPurgeTombstone() = runTest {
        val exception = mockk<JetStreamApiException>()
        every { exception.apiErrorCode } returns 10071

        every { kv.create(eq(lockName), any<ByteArray>(), any<MessageTtl>()) } throws exception

        val result = lock.tryAcquire(1000)

        assertFalse(result)
        assertFalse(lock.isHeld)
        verify(exactly = 1) { kv.create(eq(lockName), any<ByteArray>(), any<MessageTtl>()) }
        verify(exactly = 0) { kv.get(any<String>()) }
        verify(exactly = 0) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }
    }

    @Test
    fun renewSuccess() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L
        lock.tryAcquire(10_000)

        val entry = mockk<KeyValueEntry>()
        val token = getPrivateToken(lock)
        every { entry.valueAsString } returns token
        every { entry.revision } returns 1L
        every { kv.get(lockName) } returns entry
        every { kv.update(eq(lockName), any<ByteArray>(), eq(1L)) } returns 2L

        val result = lock.renew(1000)

        assertTrue(result)
        verify { kv.update(eq(lockName), any<ByteArray>(), eq(1L)) }
    }

    @Test
    fun renewFailureWhenNotOwner() = runTest {
        val entry = mockk<KeyValueEntry>()
        every { entry.valueAsString } returns "other-token"
        every { kv.get(lockName) } returns entry

        val result = lock.renew(1000)

        assertFalse(result)
        verify(exactly = 0) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }
    }

    @Test
    fun renewSucceedsWhenLocallyExpiredButStillOwnedInNats() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L
        lock.tryAcquire(1)
        Thread.sleep(5)
        assertFalse(lock.isHeld)

        val entry = mockk<KeyValueEntry>()
        val token = getPrivateToken(lock)
        every { entry.valueAsString } returns token
        every { entry.revision } returns 1L
        every { kv.get(lockName) } returns entry
        every { kv.update(eq(lockName), any<ByteArray>(), eq(1L)) } returns 2L

        val result = lock.renew(10_000)

        assertTrue(result)
        assertTrue(lock.isHeld)
        verify { kv.update(eq(lockName), any<ByteArray>(), eq(1L)) }
    }

    @Test
    fun releaseSuccess() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L
        lock.tryAcquire(10_000)

        val entry = mockk<KeyValueEntry>()
        val token = getPrivateToken(lock)
        every { entry.valueAsString } returns token
        every { entry.revision } returns 7L
        every { kv.get(lockName) } returns entry
        every { kv.purge(lockName, 7L) } just Runs

        val result = lock.release()

        assertTrue(result)
        // Only at the revision read, so a holder that took the lock after an expiry keeps it.
        verify { kv.purge(lockName, 7L) }
        verify(exactly = 0) { kv.delete(any<String>()) }
    }

    @Test
    fun releaseRetriesAtTheNewRevisionWhenTheHoldersOwnRenewalMovedIt() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L
        lock.tryAcquire(10_000)

        val token = getPrivateToken(lock)
        val read = mockk<KeyValueEntry> {
            every { valueAsString } returns token
            every { revision } returns 7L
        }
        val renewed = mockk<KeyValueEntry> {
            every { valueAsString } returns token
            every { revision } returns 8L
        }
        every { kv.get(lockName) } returnsMany listOf(read, renewed)
        every { kv.purge(lockName, 7L) } throws mockk<io.nats.client.JetStreamApiException> {
            every { apiErrorCode } returns 10071
        }
        every { kv.purge(lockName, 8L) } just Runs

        assertTrue(lock.release())
        verify { kv.purge(lockName, 8L) }
    }

    @Test
    fun isHeldReturnsFalseBeforeAcquire() {
        assertFalse(lock.isHeld)
    }

    @Test
    fun isHeldReturnsTrueAfterAcquire() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L

        lock.tryAcquire(10_000)

        assertTrue(lock.isHeld)
    }

    @Test
    fun isHeldReturnsFalseAfterRelease() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L
        lock.tryAcquire(10_000)

        val entry = mockk<KeyValueEntry>()
        val token = getPrivateToken(lock)
        every { entry.valueAsString } returns token
        every { entry.revision } returns 7L
        every { kv.get(lockName) } returns entry
        every { kv.purge(lockName, 7L) } just Runs

        lock.release()

        assertFalse(lock.isHeld)
    }

    @Test
    fun isHeldReturnsFalseAfterExpiry() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L

        lock.tryAcquire(1)
        Thread.sleep(5)

        assertFalse(lock.isHeld)
    }

    @Test
    fun isHeldReturnsTrueAfterRenew() = runTest {
        every { kv.create(any<String>(), any<ByteArray>(), any<MessageTtl>()) } returns 1L
        lock.tryAcquire(10_000)
        assertTrue(lock.isHeld)

        val entry = mockk<KeyValueEntry>()
        val token = getPrivateToken(lock)
        every { entry.valueAsString } returns token
        every { entry.revision } returns 1L
        every { kv.get(lockName) } returns entry
        every { kv.update(eq(lockName), any<ByteArray>(), eq(1L)) } returns 2L

        lock.renew(10_000)

        assertTrue(lock.isHeld)
    }

    @Test
    fun acquireWithRetry() = runTest {
        val exception = mockk<JetStreamApiException>()
        every { exception.apiErrorCode } returns 10071

        val entry = mockk<KeyValueEntry>()
        every { entry.operation } returns KeyValueOperation.PUT
        every { kv.get(lockName) } returns entry

        var count = 0
        every { kv.create(eq(lockName), any<ByteArray>(), any<MessageTtl>()) } answers {
            count++
            if (count < 3) throw exception else 1L
        }

        val result = lock.acquire(1000, waitTimeoutMillis = 500, retryDelayMillis = 10)

        assertTrue(result)
        assertEquals(3, count)
    }

    private fun getPrivateToken(lock: NatsDistributedLock): String {
        val field = lock::class.java.getDeclaredField("token")
        field.isAccessible = true
        return field.get(lock) as String
    }
}

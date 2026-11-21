package bosca.lock.nats

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.nats.client.JetStreamApiException
import io.nats.client.KeyValue
import io.nats.client.MessageTtl
import io.nats.client.api.ApiResponse
import io.nats.client.api.KeyValueEntry
import io.nats.client.support.JsonParser
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NatsDistributedLockFailureTest {

    @Test
    fun `acquisition distinguishes key contention from other API failures and unavailable transport`() = runTest(timeout = 10.seconds) {
        val kv = mockk<KeyValue>()
        val lock = NatsDistributedLock(kv, KEY)
        every { kv.create(KEY, any<ByteArray>(), any<MessageTtl>()) } throws apiFailure(10071)
        assertFalse(lock.tryAcquire(10_000))
        assertFalse(lock.isHeld)

        every { kv.create(KEY, any<ByteArray>(), any<MessageTtl>()) } throws apiFailure(10003)
        assertEquals(10003, assertFailsWith<JetStreamApiException> { lock.tryAcquire(10_000) }.apiErrorCode)

        every { kv.create(KEY, any<ByteArray>(), any<MessageTtl>()) } throws IOException("broker unavailable")
        assertFalse(lock.tryAcquire(10_000))
        assertFalse(lock.isHeld)
    }

    @Test
    fun `renewal after a revision conflict succeeds only while the token still belongs to this holder`() = runTest(timeout = 10.seconds) {
        for (replacementOwner in listOf("self", "another holder", null)) {
            val kv = mockk<KeyValue>()
            val (lock, token) = acquired(kv)
            val original = entry(token, 1)
            val replacement = replacementOwner?.let { entry(if (it == "self") token else it, 2) }
            every { kv.get(KEY) } returnsMany listOf(original, replacement)
            every { kv.update(KEY, any<ByteArray>(), 1) } throws apiFailure(10071)

            assertEquals(replacementOwner == "self", lock.renew(10_000))

            verify(exactly = 1) { kv.update(KEY, any<ByteArray>(), 1) }
            verify(exactly = 2) { kv.get(KEY) }
            verify(exactly = 0) { kv.purge(any<String>(), any<Long>()) }
        }
    }

    @Test
    fun `missing keys and renewal API failures leave the lock unrenewed`() = runTest(timeout = 10.seconds) {
        val kv = mockk<KeyValue>()
        val (lock, token) = acquired(kv)
        every { kv.get(KEY) } returns null
        assertFalse(lock.renew(10_000))
        verify(exactly = 0) { kv.update(any<String>(), any<ByteArray>(), any<Long>()) }

        every { kv.get(KEY) } returns entry(token, 1)
        every { kv.update(KEY, any<ByteArray>(), 1) } throws apiFailure(10003)
        assertFalse(lock.renew(10_000))
    }

    @Test
    fun `release never purges a missing key or a replacement owner's token`() = runTest(timeout = 10.seconds) {
        for (owner in listOf(null, "another holder")) {
            val kv = mockk<KeyValue>()
            val (lock, _) = acquired(kv)
            every { kv.get(KEY) } returns owner?.let { entry(it, 1) }

            assertFalse(lock.release())

            verify(exactly = 0) { kv.purge(any<String>(), any<Long>()) }
        }
    }

    @Test
    fun `release after a revision conflict does not purge a key that vanished or changed owner`() = runTest(timeout = 10.seconds) {
        for (owner in listOf(null, "another holder")) {
            val kv = mockk<KeyValue>()
            val (lock, token) = acquired(kv)
            every { kv.get(KEY) } returnsMany listOf(entry(token, 1), owner?.let { entry(it, 2) })
            every { kv.purge(KEY, 1) } throws apiFailure(10071)

            assertFalse(lock.release())

            verify(exactly = 1) { kv.purge(KEY, 1) }
            verify(exactly = 0) { kv.purge(KEY, 2) }
        }
    }

    @Test
    fun `release reports an unexpected purge failure and can retry the still owned key`() = runTest(timeout = 10.seconds) {
        val kv = mockk<KeyValue>()
        val (lock, token) = acquired(kv)
        every { kv.get(KEY) } returns entry(token, 1)
        every { kv.purge(KEY, 1) } throws apiFailure(10003)
        assertFalse(lock.release())

        every { kv.purge(KEY, 1) } just runs
        assertTrue(lock.release())
        assertFalse(lock.isHeld)
        assertFalse(lock.release())
        verify(exactly = 2) { kv.purge(KEY, 1) }
    }

    @Test
    fun `withLock skips work when its wait expires and releases after user code fails`() = runTest(timeout = 10.seconds) {
        val kv = mockk<KeyValue>()
        every { kv.create(KEY, any<ByteArray>(), any<MessageTtl>()) } throws apiFailure(10071)
        val contended = NatsDistributedLock(kv, KEY)
        var ran = false
        assertEquals(null, contended.withLock(10_000, 0, 1) { ran = true })
        assertFalse(ran)

        val token = slot<ByteArray>()
        every { kv.create(KEY, capture(token), any<MessageTtl>()) } returns 1
        every { kv.get(KEY) } answers { entry(token.captured.toString(Charsets.UTF_8), 1) }
        every { kv.purge(KEY, 1) } just runs
        val owned = NatsDistributedLock(kv, KEY)
        assertEquals("user code failed", assertFailsWith<IllegalArgumentException> {
            owned.withLock(10_000, 0, 1) { throw IllegalArgumentException("user code failed") }
        }.message)
        verify(exactly = 1) { kv.purge(KEY, 1) }
        assertFalse(owned.isHeld)
    }

    private suspend fun acquired(kv: KeyValue): Pair<NatsDistributedLock, String> {
        val token = slot<ByteArray>()
        every { kv.create(KEY, capture(token), any<MessageTtl>()) } returns 1
        val lock = NatsDistributedLock(kv, KEY)
        assertTrue(lock.tryAcquire(60_000))
        return lock to token.captured.toString(Charsets.UTF_8)
    }

    private fun entry(token: String, version: Long): KeyValueEntry = mockk {
        every { valueAsString } returns token
        every { revision } returns version
    }

    private fun apiFailure(code: Int): JetStreamApiException {
        val response = object : ApiResponse<Nothing>(JsonParser.parse(
            """{"error":{"code":400,"err_code":$code,"description":"test API failure"}}""",
        )) {}
        return JetStreamApiException(checkNotNull(response.errorObject))
    }

    private companion object {
        const val KEY = "failure-lock"
    }
}

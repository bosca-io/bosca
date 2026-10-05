package bosca.counter.nats

import bosca.nats.NatsConnectionPool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.StreamContext
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** [NatsCounter]: increments via JetStream's server-side message counter and reads the running total back. */
class NatsCounterTest {

    private val connection = mockk<Connection>()
    private val jsm = mockk<JetStreamManagement>(relaxed = true)
    private val js = mockk<JetStream>()
    private val streamContext = mockk<StreamContext>()
    private val pool = mockk<NatsConnectionPool>()
    private val counter = NatsCounter(pool, Json)

    init {
        coEvery { pool.systemConnection() } returns connection
        every { connection.jetStreamManagement() } returns jsm
        every { jsm.streamNames } returns emptyList() // stream absent → created on first use
        every { connection.jetStream() } returns js
        every { connection.getStreamContext(any()) } returns streamContext
    }

    @Test
    fun `increment publishes a Nats-Incr header and returns the ack total`() = runTest {
        val ack = mockk<PublishAck> { every { getVal() } returns "7" }
        val headers = slot<Headers>()
        every { js.publish("bosca.counter.req.5xx.42", capture(headers), any()) } returns ack

        assertEquals(7L, counter.increment("req.5xx.42", 3))
        assertEquals(listOf("3"), headers.captured["Nats-Incr"])
    }

    @Test
    fun `the counter stream is created once, not per increment`() = runTest {
        every { js.publish(any<String>(), any<Headers>(), any()) } returns mockk { every { getVal() } returns "1" }

        counter.increment("a")
        counter.increment("b")

        verify(exactly = 1) { jsm.addStream(any()) }
    }

    @Test
    fun `get parses the total from the subject's last message`() = runTest {
        every { streamContext.getLastMessage("bosca.counter.req.2xx") } returns
            mockk { every { data } returns """{"val":"128"}""".toByteArray() }

        assertEquals(128L, counter.get("req.2xx"))
    }

    @Test
    fun `get returns zero when the subject has no counter yet`() = runTest {
        every { streamContext.getLastMessage(any()) } throws mockk<JetStreamApiException>(relaxed = true)

        assertEquals(0L, counter.get("never-seen"))
    }

    @Test
    fun `get accepts empty bare malformed and incomplete counter payloads`() = runTest {
        val payloads = mutableMapOf(
            "null" to null,
            "empty" to byteArrayOf(),
            "bare" to " 42 ".toByteArray(),
            "malformed" to "not-a-counter".toByteArray(),
            "missing" to "{}".toByteArray(),
            "invalid" to """{"val":"not-a-number"}""".toByteArray(),
        )
        every { streamContext.getLastMessage(any()) } answers {
            val key = firstArg<String>().substringAfterLast('.')
            mockk { every { data } returns payloads[key] }
        }

        assertEquals(0L, counter.get("null"))
        assertEquals(0L, counter.get("empty"))
        assertEquals(42L, counter.get("bare"))
        assertEquals(0L, counter.get("malformed"))
        assertEquals(0L, counter.get("missing"))
        assertEquals(0L, counter.get("invalid"))
        assertEquals(mapOf("bare" to 42L, "empty" to 0L), counter.get(listOf("bare", "empty")))
        assertEquals(emptyMap(), counter.get(emptyList()))
    }

    @Test
    fun `increment returns zero for absent or malformed acknowledgement totals`() = runTest {
        every { js.publish(any<String>(), any<Headers>(), any()) } returnsMany listOf(
            mockk { every { getVal() } returns null },
            mockk { every { getVal() } returns "not-a-number" },
        )

        assertEquals(0L, counter.increment("absent"))
        assertEquals(0L, counter.increment("malformed"))
    }

    @Test
    fun `existing stream skips creation and management failures remain retry safe`() = runTest {
        every { jsm.streamNames } returns listOf("BOSCA_COUNTERS")
        every { js.publish(any<String>(), any<Headers>(), any()) } returns mockk { every { getVal() } returns "1" }
        counter.increment("existing")
        verify(exactly = 0) { jsm.addStream(any()) }

        val failingManagement = mockk<JetStreamManagement>()
        every { failingManagement.streamNames } throws IllegalStateException("management unavailable")
        every { failingManagement.addStream(any()) } throws IllegalStateException("concurrent creator")
        every { connection.jetStreamManagement() } returns failingManagement
        val fresh = NatsCounter(pool, Json)
        assertEquals(1L, fresh.increment("raced"))
        verify { failingManagement.addStream(any()) }
    }
}

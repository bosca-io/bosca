package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NatsKeyValueStoreTest {

    @Test
    fun `NatsKeyValueStore stores all fields`() {
        val store = NatsKeyValueStore(
            bucket = "my-bucket",
            entryCount = 100,
            bytes = 50000,
            ttl = 3600,
            maxBytes = 1048576,
            maxValueSize = 8192,
            history = 5
        )
        assertEquals("my-bucket", store.bucket)
        assertEquals(100, store.entryCount)
        assertEquals(50000, store.bytes)
        assertEquals(3600, store.ttl)
        assertEquals(1048576, store.maxBytes)
        assertEquals(8192, store.maxValueSize)
        assertEquals(5, store.history)
    }

    @Test
    fun `NatsKeyValueEntry stores all fields`() {
        val entry = NatsKeyValueEntry(
            key = "config.key",
            value = "some-value",
            revision = 3,
            created = "2024-01-01T00:00:00Z",
            operation = "PUT"
        )
        assertEquals("config.key", entry.key)
        assertEquals("some-value", entry.value)
        assertEquals(3, entry.revision)
        assertEquals("2024-01-01T00:00:00Z", entry.created)
        assertEquals("PUT", entry.operation)
    }

    @Test
    fun `NatsKeyValueEntry with null value`() {
        val entry = NatsKeyValueEntry(
            key = "deleted-key",
            value = null,
            revision = 5,
            created = "2024-06-01T00:00:00Z",
            operation = "DEL"
        )
        assertNull(entry.value)
        assertEquals("DEL", entry.operation)
    }

    @Test
    fun `NatsStreamMessage stores all fields`() {
        val headers = listOf(
            NatsHeader(key = "Nats-Msg-Id", values = listOf("msg-1")),
            NatsHeader(key = "Content-Type", values = listOf("application/json"))
        )
        val msg = NatsStreamMessage(
            subject = "orders.created",
            sequence = 42,
            timestamp = "2024-01-01T00:00:00Z",
            data = """{"orderId": "123"}""",
            headers = headers
        )
        assertEquals("orders.created", msg.subject)
        assertEquals(42, msg.sequence)
        assertEquals("2024-01-01T00:00:00Z", msg.timestamp)
        assertEquals("""{"orderId": "123"}""", msg.data)
        assertEquals(2, msg.headers.size)
        assertEquals("Nats-Msg-Id", msg.headers[0].key)
        assertEquals(listOf("msg-1"), msg.headers[0].values)
    }

    @Test
    fun `NatsStreamMessage with null data`() {
        val msg = NatsStreamMessage(
            subject = "test",
            sequence = 1,
            timestamp = "2024-01-01T00:00:00Z",
            data = null,
            headers = emptyList()
        )
        assertNull(msg.data)
    }

    @Test
    fun `NatsHeader with multiple values`() {
        val header = NatsHeader(key = "Accept", values = listOf("text/plain", "application/json"))
        assertEquals("Accept", header.key)
        assertEquals(2, header.values.size)
    }
}

package bosca.nats.admin.graphql

import bosca.nats.admin.model.NatsHeader
import bosca.nats.admin.model.NatsKeyValueEntry
import bosca.nats.admin.model.NatsKeyValueStore
import bosca.nats.admin.model.NatsStreamMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NatsKeyValueStoreControllerTest {

    private val controller = NatsKeyValueStoreController()

    private val store = NatsKeyValueStore(
        bucket = "my-bucket",
        entryCount = 42,
        bytes = 10240,
        ttl = 60000,
        maxBytes = 1048576,
        maxValueSize = 32768,
        history = 5
    )

    @Test
    fun `bucket delegates to model field`() {
        assertEquals("my-bucket", controller.bucket(store))
    }

    @Test
    fun `entryCount delegates to model field`() {
        assertEquals(42, controller.entryCount(store))
    }

    @Test
    fun `bytes delegates to model field`() {
        assertEquals(10240, controller.bytes(store))
    }

    @Test
    fun `ttl delegates to model field`() {
        assertEquals(60000, controller.ttl(store))
    }

    @Test
    fun `maxBytes delegates to model field`() {
        assertEquals(1048576, controller.maxBytes(store))
    }

    @Test
    fun `maxValueSize delegates to model field`() {
        assertEquals(32768, controller.maxValueSize(store))
    }

    @Test
    fun `history delegates to model field`() {
        assertEquals(5, controller.history(store))
    }
}

class NatsKeyValueEntryControllerTest {

    private val controller = NatsKeyValueEntryController()

    private val entry = NatsKeyValueEntry(
        key = "config.timeout",
        value = "30000",
        revision = 3,
        created = "2024-06-15T10:00:00Z",
        operation = "PUT"
    )

    @Test
    fun `key delegates to model field`() {
        assertEquals("config.timeout", controller.key(entry))
    }

    @Test
    fun `value delegates to model field`() {
        assertEquals("30000", controller.value(entry))
    }

    @Test
    fun `revision delegates to model field`() {
        assertEquals(3, controller.revision(entry))
    }

    @Test
    fun `created delegates to model field`() {
        assertEquals("2024-06-15T10:00:00Z", controller.created(entry))
    }

    @Test
    fun `operation delegates to model field`() {
        assertEquals("PUT", controller.operation(entry))
    }

    @Test
    fun `value can be null`() {
        val nullEntry = NatsKeyValueEntry(
            key = "deleted-key", value = null, revision = 5, created = "", operation = "DELETE"
        )
        assertNull(controller.value(nullEntry))
    }
}

class NatsHeaderControllerTest {

    private val controller = NatsHeaderController()

    @Test
    fun `key delegates to model field`() {
        val header = NatsHeader(key = "Content-Type", values = listOf("application/json"))
        assertEquals("Content-Type", controller.key(header))
    }

    @Test
    fun `values delegates to model field`() {
        val header = NatsHeader(key = "Accept", values = listOf("text/plain", "application/json"))
        val result = controller.values(header)
        assertEquals(2, result.size)
        assertEquals("text/plain", result[0])
        assertEquals("application/json", result[1])
    }

    @Test
    fun `values returns empty list when no values`() {
        val header = NatsHeader(key = "Empty", values = emptyList())
        assertTrue(controller.values(header).isEmpty())
    }
}

class NatsStreamMessageControllerTest {

    private val controller = NatsStreamMessageController()

    private val message = NatsStreamMessage(
        subject = "orders.created",
        sequence = 42,
        timestamp = "2024-06-15T10:00:00Z",
        data = """{"orderId": "abc123"}""",
        headers = listOf(
            NatsHeader(key = "Nats-Msg-Id", values = listOf("msg-42"))
        )
    )

    @Test
    fun `subject delegates to model field`() {
        assertEquals("orders.created", controller.subject(message))
    }

    @Test
    fun `sequence delegates to model field`() {
        assertEquals(42, controller.sequence(message))
    }

    @Test
    fun `timestamp delegates to model field`() {
        assertEquals("2024-06-15T10:00:00Z", controller.timestamp(message))
    }

    @Test
    fun `data delegates to model field`() {
        assertEquals("""{"orderId": "abc123"}""", controller.data(message))
    }

    @Test
    fun `headers delegates to model field`() {
        val result = controller.headers(message)
        assertEquals(1, result.size)
        assertEquals("Nats-Msg-Id", result[0].key)
    }

    @Test
    fun `data can be null`() {
        val noData = NatsStreamMessage(
            subject = "test", sequence = 1, timestamp = "", data = null, headers = emptyList()
        )
        assertNull(controller.data(noData))
    }

    @Test
    fun `headers can be empty`() {
        val noHeaders = NatsStreamMessage(
            subject = "test", sequence = 1, timestamp = "", data = "x", headers = emptyList()
        )
        assertTrue(controller.headers(noHeaders).isEmpty())
    }
}

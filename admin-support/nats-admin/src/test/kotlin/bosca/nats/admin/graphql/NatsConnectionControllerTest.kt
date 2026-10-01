package bosca.nats.admin.graphql

import bosca.nats.admin.model.NatsConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NatsConnectionControllerTest {

    private val controller = NatsConnectionController()

    private val connection = NatsConnection(
        cid = 42,
        kind = "Client",
        type = "nats",
        ip = "10.0.0.1",
        port = 52001,
        start = "2024-01-01T00:00:00Z",
        lastActivity = "2024-01-01T01:00:00Z",
        rtt = "1ms",
        uptime = "2h30m",
        idle = "10s",
        pendingBytes = 256,
        inMsgs = 100,
        outMsgs = 200,
        inBytes = 5000,
        outBytes = 10000,
        subscriptions = 3,
        name = "my-client",
        lang = "java",
        version = "2.16.0"
    )

    @Test
    fun `cid delegates to model field`() {
        assertEquals(42, controller.cid(connection))
    }

    @Test
    fun `kind delegates to model field`() {
        assertEquals("Client", controller.kind(connection))
    }

    @Test
    fun `type delegates to model field`() {
        assertEquals("nats", controller.type(connection))
    }

    @Test
    fun `ip delegates to model field`() {
        assertEquals("10.0.0.1", controller.ip(connection))
    }

    @Test
    fun `port delegates to model field`() {
        assertEquals(52001, controller.port(connection))
    }

    @Test
    fun `start delegates to model field`() {
        assertEquals("2024-01-01T00:00:00Z", controller.start(connection))
    }

    @Test
    fun `lastActivity delegates to model field`() {
        assertEquals("2024-01-01T01:00:00Z", controller.lastActivity(connection))
    }

    @Test
    fun `rtt delegates to model field`() {
        assertEquals("1ms", controller.rtt(connection))
    }

    @Test
    fun `uptime delegates to model field`() {
        assertEquals("2h30m", controller.uptime(connection))
    }

    @Test
    fun `idle delegates to model field`() {
        assertEquals("10s", controller.idle(connection))
    }

    @Test
    fun `pendingBytes delegates to model field`() {
        assertEquals(256, controller.pendingBytes(connection))
    }

    @Test
    fun `inMsgs delegates to model field`() {
        assertEquals(100, controller.inMsgs(connection))
    }

    @Test
    fun `outMsgs delegates to model field`() {
        assertEquals(200, controller.outMsgs(connection))
    }

    @Test
    fun `inBytes delegates to model field`() {
        assertEquals(5000, controller.inBytes(connection))
    }

    @Test
    fun `outBytes delegates to model field`() {
        assertEquals(10000, controller.outBytes(connection))
    }

    @Test
    fun `subscriptions delegates to model field`() {
        assertEquals(3, controller.subscriptions(connection))
    }

    @Test
    fun `name delegates to model field`() {
        assertEquals("my-client", controller.name(connection))
    }

    @Test
    fun `lang delegates to model field`() {
        assertEquals("java", controller.lang(connection))
    }

    @Test
    fun `version delegates to model field`() {
        assertEquals("2.16.0", controller.version(connection))
    }

    @Test
    fun `nullable fields return null when not set`() {
        val minimal = NatsConnection(cid = 1, ip = "0.0.0.0", port = 1, uptime = "0s", idle = "0s")
        assertNull(controller.kind(minimal))
        assertNull(controller.type(minimal))
        assertNull(controller.start(minimal))
        assertNull(controller.lastActivity(minimal))
        assertNull(controller.rtt(minimal))
        assertNull(controller.name(minimal))
        assertNull(controller.lang(minimal))
        assertNull(controller.version(minimal))
    }
}

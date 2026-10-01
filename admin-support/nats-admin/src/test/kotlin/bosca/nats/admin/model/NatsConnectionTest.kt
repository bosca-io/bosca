package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NatsConnectionTest {

    @Test
    fun `NatsConnection stores required fields`() {
        val conn = NatsConnection(
            cid = 42,
            ip = "192.168.1.1",
            port = 4222,
            uptime = "2h30m",
            idle = "10s"
        )
        assertEquals(42, conn.cid)
        assertEquals("192.168.1.1", conn.ip)
        assertEquals(4222, conn.port)
        assertEquals("2h30m", conn.uptime)
        assertEquals("10s", conn.idle)
    }

    @Test
    fun `NatsConnection has sensible defaults`() {
        val conn = NatsConnection(cid = 1, ip = "0.0.0.0", port = 1, uptime = "0s", idle = "0s")
        assertNull(conn.kind)
        assertNull(conn.type)
        assertNull(conn.start)
        assertNull(conn.lastActivity)
        assertNull(conn.rtt)
        assertEquals(0, conn.pendingBytes)
        assertEquals(0, conn.inMsgs)
        assertEquals(0, conn.outMsgs)
        assertEquals(0, conn.inBytes)
        assertEquals(0, conn.outBytes)
        assertEquals(0, conn.subscriptions)
        assertNull(conn.name)
        assertNull(conn.lang)
        assertNull(conn.version)
    }

    @Test
    fun `NatsConnection with all optional fields`() {
        val conn = NatsConnection(
            cid = 5,
            kind = "Client",
            type = "nats",
            ip = "10.0.0.1",
            port = 52001,
            start = "2024-01-01T00:00:00Z",
            lastActivity = "2024-01-01T01:00:00Z",
            rtt = "1ms",
            uptime = "1h",
            idle = "30s",
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
        assertEquals("Client", conn.kind)
        assertEquals("nats", conn.type)
        assertEquals("2024-01-01T00:00:00Z", conn.start)
        assertEquals("1ms", conn.rtt)
        assertEquals(256, conn.pendingBytes)
        assertEquals(3, conn.subscriptions)
        assertEquals("my-client", conn.name)
        assertEquals("java", conn.lang)
        assertEquals("2.16.0", conn.version)
    }

    @Test
    fun `NatsConnection equality`() {
        val a = NatsConnection(cid = 1, ip = "1.1.1.1", port = 1, uptime = "1s", idle = "1s")
        val b = NatsConnection(cid = 1, ip = "1.1.1.1", port = 1, uptime = "1s", idle = "1s")
        assertEquals(a, b)
    }
}

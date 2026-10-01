package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NatsServerInfoTest {

    @Test
    fun `NatsServerInfo stores all required fields`() {
        val info = NatsServerInfo(
            serverId = "server-1",
            serverName = "my-nats",
            version = "2.10.0",
            host = "0.0.0.0",
            port = 4222,
            maxConnections = 65536,
            maxPayload = 1048576,
            uptime = "24h0m0s",
            mem = 134217728,
            cpu = 1.5,
            connections = 10,
            totalConnections = 100,
            subscriptions = 50,
            slowConsumers = 0,
            inMsgs = 1000,
            outMsgs = 2000,
            inBytes = 50000,
            outBytes = 100000
        )
        assertEquals("server-1", info.serverId)
        assertEquals("my-nats", info.serverName)
        assertEquals("2.10.0", info.version)
        assertEquals("0.0.0.0", info.host)
        assertEquals(4222, info.port)
        assertEquals(65536, info.maxConnections)
        assertEquals(1048576, info.maxPayload)
        assertEquals("24h0m0s", info.uptime)
        assertEquals(134217728, info.mem)
        assertEquals(1.5, info.cpu)
        assertEquals(10, info.connections)
        assertEquals(100, info.totalConnections)
        assertEquals(50, info.subscriptions)
        assertEquals(0, info.slowConsumers)
        assertEquals(1000, info.inMsgs)
        assertEquals(2000, info.outMsgs)
        assertEquals(50000, info.inBytes)
        assertEquals(100000, info.outBytes)
        assertNull(info.jetstream)
    }

    @Test
    fun `NatsServerInfo with JetStream configuration`() {
        val jetstream = JetStreamServerConfig(
            config = JetStreamConfig(maxMemory = 1024, maxStorage = 2048, storeDir = "/data"),
            stats = JetStreamStats(memory = 512, storage = 1024)
        )
        val info = NatsServerInfo(
            serverId = "s1",
            serverName = "n1",
            version = "2.10.0",
            host = "localhost",
            port = 4222,
            maxConnections = 100,
            maxPayload = 1048576,
            uptime = "1h",
            mem = 0,
            cpu = 0.0,
            connections = 0,
            totalConnections = 0,
            subscriptions = 0,
            slowConsumers = 0,
            inMsgs = 0,
            outMsgs = 0,
            inBytes = 0,
            outBytes = 0,
            jetstream = jetstream
        )
        assertEquals(jetstream, info.jetstream)
        assertEquals(1024, info.jetstream?.config?.maxMemory)
        assertEquals("/data", info.jetstream?.config?.storeDir)
    }

    @Test
    fun `NatsServerInfo equality based on all fields`() {
        val a = NatsServerInfo(
            serverId = "s1", serverName = "n1", version = "v1", host = "h",
            port = 1, maxConnections = 1, maxPayload = 1, uptime = "1s",
            mem = 1, cpu = 0.1, connections = 1, totalConnections = 1,
            subscriptions = 1, slowConsumers = 0, inMsgs = 0, outMsgs = 0,
            inBytes = 0, outBytes = 0
        )
        val b = a.copy()
        assertEquals(a, b)
    }
}

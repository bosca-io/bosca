package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NatsConnectionsTest {

    @Test
    fun `NatsConnections defaults to empty connections list`() {
        val connections = NatsConnections(
            numConnections = 0,
            total = 0,
            offset = 0,
            limit = 100
        )
        assertTrue(connections.connections.isEmpty())
    }

    @Test
    fun `NatsConnections stores pagination fields`() {
        val connections = NatsConnections(
            numConnections = 5,
            total = 50,
            offset = 10,
            limit = 20
        )
        assertEquals(5, connections.numConnections)
        assertEquals(50, connections.total)
        assertEquals(10, connections.offset)
        assertEquals(20, connections.limit)
    }

    @Test
    fun `NatsConnections with connection list`() {
        val conn = NatsConnection(
            cid = 1,
            ip = "127.0.0.1",
            port = 52000,
            uptime = "1h",
            idle = "5s"
        )
        val connections = NatsConnections(
            numConnections = 1,
            total = 1,
            offset = 0,
            limit = 100,
            connections = listOf(conn)
        )
        assertEquals(1, connections.connections.size)
        assertEquals(1L, connections.connections.first().cid)
    }
}

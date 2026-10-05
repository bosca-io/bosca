package bosca.nats.admin.graphql

import bosca.nats.admin.model.NatsConnection
import bosca.nats.admin.model.NatsConnections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NatsConnectionsControllerTest {

    private val controller = NatsConnectionsController()

    private val sampleConnection = NatsConnection(
        cid = 1, ip = "10.0.0.1", port = 4222, uptime = "1h", idle = "5s"
    )

    @Test
    fun `numConnections delegates to model field`() {
        val conns = NatsConnections(numConnections = 5, total = 100, offset = 0, limit = 50, connections = emptyList())
        assertEquals(5, controller.numConnections(conns))
    }

    @Test
    fun `total delegates to model field`() {
        val conns = NatsConnections(numConnections = 5, total = 100, offset = 0, limit = 50, connections = emptyList())
        assertEquals(100, controller.total(conns))
    }

    @Test
    fun `offset delegates to model field`() {
        val conns = NatsConnections(numConnections = 5, total = 100, offset = 10, limit = 50, connections = emptyList())
        assertEquals(10, controller.offset(conns))
    }

    @Test
    fun `limit delegates to model field`() {
        val conns = NatsConnections(numConnections = 5, total = 100, offset = 0, limit = 25, connections = emptyList())
        assertEquals(25, controller.limit(conns))
    }

    @Test
    fun `connections returns the list from model`() {
        val list = listOf(sampleConnection)
        val conns = NatsConnections(numConnections = 1, total = 1, offset = 0, limit = 50, connections = list)
        val result = controller.connections(conns)
        assertEquals(1, result.size)
        assertEquals(1, result[0].cid)
    }

    @Test
    fun `connections returns empty list when no connections`() {
        val conns = NatsConnections(numConnections = 0, total = 0, offset = 0, limit = 50, connections = emptyList())
        assertTrue(controller.connections(conns).isEmpty())
    }
}

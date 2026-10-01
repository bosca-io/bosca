package bosca.nats.admin.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NatsConnectionsDeserializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes connz response with connections`() {
        val body = """
        {
          "num_connections": 2,
          "total": 5,
          "offset": 0,
          "limit": 100,
          "connections": [
            {
              "cid": 1,
              "kind": "Client",
              "type": "nats",
              "ip": "127.0.0.1",
              "port": 51234,
              "start": "2024-06-24T10:00:00Z",
              "last_activity": "2024-06-24T14:29:00Z",
              "rtt": "500µs",
              "uptime": "4h29m",
              "idle": "16s",
              "pending_bytes": 0,
              "in_msgs": 1200,
              "out_msgs": 1100,
              "in_bytes": 24576,
              "out_bytes": 22528,
              "subscriptions": 5,
              "name": "my-service",
              "lang": "go",
              "version": "1.31.0"
            },
            {
              "cid": 2,
              "ip": "192.168.1.10",
              "port": 52345,
              "uptime": "1h",
              "idle": "30s",
              "subscriptions": 2
            }
          ]
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsConnections>(body)
        assertEquals(2, result.numConnections)
        assertEquals(5, result.total)
        assertEquals(0, result.offset)
        assertEquals(100, result.limit)
        assertEquals(2, result.connections.size)

        val conn1 = result.connections[0]
        assertEquals(1L, conn1.cid)
        assertEquals("Client", conn1.kind)
        assertEquals("nats", conn1.type)
        assertEquals("127.0.0.1", conn1.ip)
        assertEquals(51234, conn1.port)
        assertEquals("500µs", conn1.rtt)
        assertEquals("4h29m", conn1.uptime)
        assertEquals("16s", conn1.idle)
        assertEquals(1200L, conn1.inMsgs)
        assertEquals("my-service", conn1.name)
        assertEquals("go", conn1.lang)
        assertEquals("1.31.0", conn1.version)

        val conn2 = result.connections[1]
        assertEquals(2L, conn2.cid)
        assertNull(conn2.kind)
        assertNull(conn2.name)
    }

    @Test
    fun `deserializes connz response with empty connections`() {
        val body = """
        {
          "num_connections": 0,
          "total": 0,
          "offset": 0,
          "limit": 100,
          "connections": []
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsConnections>(body)
        assertEquals(0, result.numConnections)
        assertEquals(0, result.connections.size)
    }
}

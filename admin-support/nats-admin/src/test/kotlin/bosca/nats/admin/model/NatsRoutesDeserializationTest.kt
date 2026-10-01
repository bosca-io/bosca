package bosca.nats.admin.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NatsRoutesDeserializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes routez response with routes`() {
        val body = """
        {
          "server_id": "NACDVKFBUW4C4XA24OOT6L4MDP56MW76J5RJDFXG7HLABSB46DCMWCOW",
          "now": "2024-06-24T14:29:16.046656-07:00",
          "num_routes": 2,
          "routes": [
            {
              "rid": 1,
              "remote_id": "de475c0041418afc799bccf0fdd61b47",
              "remote_name": "server-2",
              "did_solicit": true,
              "is_configured": true,
              "ip": "10.0.0.2",
              "port": 6222,
              "pending_size": 128,
              "in_msgs": 5000,
              "out_msgs": 4800,
              "in_bytes": 102400,
              "out_bytes": 98304,
              "subscriptions": 42
            },
            {
              "rid": 2,
              "remote_id": "ab123c0041418afc799bccf0fdd61b99",
              "remote_name": "server-3",
              "did_solicit": false,
              "is_configured": false,
              "ip": "10.0.0.3",
              "port": 6222,
              "pending_size": 0,
              "in_msgs": 3200,
              "out_msgs": 3100,
              "in_bytes": 65536,
              "out_bytes": 63488,
              "subscriptions": 15
            }
          ]
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsRoutes>(body)
        assertEquals(2, result.numRoutes)
        assertEquals(2, result.routes.size)

        val route1 = result.routes[0]
        assertEquals(1L, route1.rid)
        assertEquals("de475c0041418afc799bccf0fdd61b47", route1.remoteId)
        assertEquals("server-2", route1.remoteName)
        assertTrue(route1.didSolicit)
        assertTrue(route1.isConfigured)
        assertEquals("10.0.0.2", route1.ip)
        assertEquals(6222, route1.port)
        assertEquals(128L, route1.pendingSize)
        assertEquals(5000L, route1.inMsgs)
        assertEquals(4800L, route1.outMsgs)
        assertEquals(102400L, route1.inBytes)
        assertEquals(98304L, route1.outBytes)
        assertEquals(42, route1.subscriptions)

        val route2 = result.routes[1]
        assertEquals(2L, route2.rid)
        assertEquals("server-3", route2.remoteName)
        assertFalse(route2.didSolicit)
        assertFalse(route2.isConfigured)
    }

    @Test
    fun `deserializes routez response with no routes`() {
        val body = """
        {
          "server_id": "NABC123",
          "now": "2024-06-24T14:29:16Z",
          "num_routes": 0,
          "routes": []
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsRoutes>(body)
        assertEquals(0, result.numRoutes)
        assertTrue(result.routes.isEmpty())
    }

    @Test
    fun `deserializes routez response with missing optional fields`() {
        val body = """
        {
          "num_routes": 1,
          "routes": [
            {
              "rid": 5
            }
          ]
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsRoutes>(body)
        assertEquals(1, result.numRoutes)
        val route = result.routes[0]
        assertEquals(5L, route.rid)
        assertEquals("", route.remoteId)
        assertEquals("", route.remoteName)
        assertFalse(route.didSolicit)
        assertFalse(route.isConfigured)
        assertEquals("", route.ip)
        assertEquals(0, route.port)
        assertEquals(0L, route.pendingSize)
        assertEquals(0L, route.inMsgs)
        assertEquals(0L, route.outMsgs)
        assertEquals(0L, route.inBytes)
        assertEquals(0L, route.outBytes)
        assertEquals(0, route.subscriptions)
    }

    @Test
    fun `ignores unknown fields from newer NATS versions`() {
        val body = """
        {
          "num_routes": 1,
          "routes": [
            {
              "rid": 1,
              "remote_id": "abc",
              "some_future_field": "value",
              "another_unknown": 42
            }
          ]
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsRoutes>(body)
        assertEquals(1, result.numRoutes)
        assertEquals("abc", result.routes[0].remoteId)
    }
}

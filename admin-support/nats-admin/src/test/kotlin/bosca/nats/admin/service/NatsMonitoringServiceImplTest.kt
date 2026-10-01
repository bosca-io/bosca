package bosca.nats.admin.service

import bosca.di.ObjectProvider
import bosca.nats.NatsConnectionPool
import bosca.nats.admin.configuration.NatsMonitoringConfig
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NatsMonitoringServiceImplTest {

    private lateinit var httpServer: HttpServer
    private lateinit var service: NatsMonitoringServiceImpl
    private var serverPort: Int = 0

    @BeforeTest
    fun setup() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = httpServer.address.port
        httpServer.start()

        val config = NatsMonitoringConfig(monitoringUrl = "http://localhost:$serverPort")
        val connectionPool = mockk<ObjectProvider<NatsConnectionPool>>()
        service = NatsMonitoringServiceImpl(config, connectionPool)
    }

    @AfterTest
    fun teardown() {
        httpServer.stop(0)
    }

    private fun serveJson(path: String, json: String) {
        httpServer.createContext(path) { exchange ->
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun serveError(path: String, statusCode: Int) {
        httpServer.createContext(path) { exchange ->
            exchange.sendResponseHeaders(statusCode, -1)
            exchange.close()
        }
    }

    @Test
    fun `getServerInfo fetches and deserializes varz`() = runTest {
        serveJson("/varz", """
        {
          "server_id": "SRV1",
          "server_name": "test-server",
          "version": "2.10.4",
          "host": "0.0.0.0",
          "port": 4222,
          "max_connections": 1024,
          "max_payload": 1048576,
          "uptime": "5m",
          "mem": 8192,
          "cpu": 0.5,
          "connections": 3,
          "total_connections": 10,
          "subscriptions": 20,
          "slow_consumers": 0,
          "in_msgs": 500,
          "out_msgs": 480,
          "in_bytes": 10240,
          "out_bytes": 9800
        }
        """)

        val info = service.getServerInfo()
        assertEquals("SRV1", info.serverId)
        assertEquals("test-server", info.serverName)
        assertEquals("2.10.4", info.version)
        assertEquals(3, info.connections)
        assertEquals(500L, info.inMsgs)
    }

    @Test
    fun `getClusterRoutes fetches and deserializes routez`() = runTest {
        serveJson("/routez", """
        {
          "server_id": "SRV1",
          "num_routes": 2,
          "routes": [
            {
              "rid": 1,
              "remote_id": "SRV2",
              "remote_name": "node-2",
              "did_solicit": true,
              "is_configured": true,
              "ip": "10.0.0.2",
              "port": 6222,
              "pending_size": 0,
              "in_msgs": 1000,
              "out_msgs": 950,
              "in_bytes": 20480,
              "out_bytes": 19456,
              "subscriptions": 10
            },
            {
              "rid": 2,
              "remote_id": "SRV3",
              "remote_name": "node-3",
              "did_solicit": false,
              "is_configured": false,
              "ip": "10.0.0.3",
              "port": 6222,
              "pending_size": 64,
              "in_msgs": 800,
              "out_msgs": 790,
              "in_bytes": 16384,
              "out_bytes": 16000,
              "subscriptions": 5
            }
          ]
        }
        """)

        val routes = service.getClusterRoutes()
        assertEquals(2, routes.numRoutes)
        assertEquals(2, routes.routes.size)

        val route1 = routes.routes[0]
        assertEquals(1L, route1.rid)
        assertEquals("SRV2", route1.remoteId)
        assertEquals("node-2", route1.remoteName)
        assertTrue(route1.didSolicit)
        assertTrue(route1.isConfigured)
        assertEquals("10.0.0.2", route1.ip)
        assertEquals(6222, route1.port)
        assertEquals(0L, route1.pendingSize)
        assertEquals(1000L, route1.inMsgs)
        assertEquals(10, route1.subscriptions)

        val route2 = routes.routes[1]
        assertEquals(2L, route2.rid)
        assertEquals("SRV3", route2.remoteId)
        assertFalse(route2.didSolicit)
        assertEquals(64L, route2.pendingSize)
    }

    @Test
    fun `getClusterRoutes handles standalone server with no routes`() = runTest {
        serveJson("/routez", """
        {
          "server_id": "SRV1",
          "num_routes": 0,
          "routes": []
        }
        """)

        val routes = service.getClusterRoutes()
        assertEquals(0, routes.numRoutes)
        assertTrue(routes.routes.isEmpty())
    }

    @Test
    fun `getConnections fetches with pagination params`() = runTest {
        serveJson("/connz", """
        {
          "num_connections": 1,
          "total": 1,
          "offset": 0,
          "limit": 50,
          "connections": [
            {
              "cid": 42,
              "ip": "192.168.1.1",
              "port": 55555,
              "uptime": "10m",
              "idle": "2s",
              "subscriptions": 3
            }
          ]
        }
        """)

        val conns = service.getConnections(limit = 50, offset = 0)
        assertEquals(1, conns.numConnections)
        assertEquals(42L, conns.connections[0].cid)
    }

    @Test
    fun `getSubscriptionsInfo fetches and deserializes subsz`() = runTest {
        serveJson("/subsz", """
        {
          "num_subscriptions": 100,
          "num_cache": 50,
          "num_inserts": 200,
          "num_removes": 100,
          "num_matches": 5000,
          "cache_hit_rate": 0.85,
          "max_fanout": 10,
          "avg_fanout": 2.5
        }
        """)

        val subs = service.getSubscriptionsInfo()
        assertEquals(100L, subs.numSubscriptions)
        assertEquals(0.85, subs.cacheHitRate, 0.001)
        assertEquals(10, subs.maxFanout)
    }

    @Test
    fun `service throws on HTTP error response`() = runTest {
        serveError("/varz", 503)

        assertFailsWith<java.io.IOException> {
            service.getServerInfo()
        }
    }

    @Test
    fun `service wraps connect failure with the target url`() = runTest {
        // Stop the stub so nothing listens on serverPort: the connect is refused,
        // which routes through the same wrapping as a connect timeout.
        httpServer.stop(0)

        val error = assertFailsWith<java.io.IOException> {
            service.getServerInfo()
        }
        val message = error.message ?: ""
        assertTrue(message.contains("http://localhost:$serverPort"), "message should name the target url: $message")
        assertTrue(message.contains("unreachable"), "message should explain the endpoint is unreachable: $message")
    }
}

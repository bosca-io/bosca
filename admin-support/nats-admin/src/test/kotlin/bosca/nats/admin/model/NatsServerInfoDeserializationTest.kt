package bosca.nats.admin.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NatsServerInfoDeserializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes varz response`() {
        val body = """
        {
          "server_id": "NABC123",
          "server_name": "my-nats",
          "version": "2.10.4",
          "host": "0.0.0.0",
          "port": 4222,
          "max_connections": 65536,
          "max_payload": 1048576,
          "uptime": "2d3h15m",
          "mem": 52428800,
          "cpu": 1.5,
          "connections": 10,
          "total_connections": 150,
          "subscriptions": 300,
          "slow_consumers": 2,
          "in_msgs": 1000000,
          "out_msgs": 999000,
          "in_bytes": 524288000,
          "out_bytes": 520093696
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsServerInfo>(body)
        assertEquals("NABC123", result.serverId)
        assertEquals("my-nats", result.serverName)
        assertEquals("2.10.4", result.version)
        assertEquals("0.0.0.0", result.host)
        assertEquals(4222, result.port)
        assertEquals(65536, result.maxConnections)
        assertEquals(1048576, result.maxPayload)
        assertEquals("2d3h15m", result.uptime)
        assertEquals(52428800L, result.mem)
        assertEquals(1.5, result.cpu)
        assertEquals(10, result.connections)
        assertEquals(150L, result.totalConnections)
        assertEquals(300L, result.subscriptions)
        assertEquals(2L, result.slowConsumers)
        assertEquals(1000000L, result.inMsgs)
        assertEquals(999000L, result.outMsgs)
        assertEquals(524288000L, result.inBytes)
        assertEquals(520093696L, result.outBytes)
        assertNull(result.jetstream)
    }

    @Test
    fun `deserializes varz response with jetstream`() {
        val body = """
        {
          "server_id": "NABC123",
          "server_name": "my-nats",
          "version": "2.10.4",
          "host": "0.0.0.0",
          "port": 4222,
          "max_connections": 65536,
          "max_payload": 1048576,
          "uptime": "1h",
          "mem": 1024,
          "cpu": 0.1,
          "connections": 1,
          "total_connections": 1,
          "subscriptions": 5,
          "slow_consumers": 0,
          "in_msgs": 100,
          "out_msgs": 90,
          "in_bytes": 2048,
          "out_bytes": 1800,
          "jetstream": {
            "config": {
              "max_memory": 1073741824,
              "max_storage": 10737418240,
              "store_dir": "/data/jetstream"
            },
            "stats": {
              "memory": 536870912,
              "storage": 5368709120,
              "reserved_memory": 268435456,
              "reserved_storage": 2684354560,
              "accounts": 1,
              "ha_assets": 3,
              "api": {
                "total": 5000,
                "errors": 12
              }
            }
          }
        }
        """.trimIndent()

        val result = json.decodeFromString<NatsServerInfo>(body)
        val js = assertNotNull(result.jetstream)
        val config = assertNotNull(js.config)
        assertEquals(1073741824L, config.maxMemory)
        assertEquals(10737418240L, config.maxStorage)
        assertEquals("/data/jetstream", config.storeDir)
        val stats = assertNotNull(js.stats)
        assertEquals(536870912L, stats.memory)
        assertEquals(3, stats.haAssets)
        val api = assertNotNull(stats.api)
        assertEquals(5000L, api.total)
        assertEquals(12L, api.errors)
    }
}

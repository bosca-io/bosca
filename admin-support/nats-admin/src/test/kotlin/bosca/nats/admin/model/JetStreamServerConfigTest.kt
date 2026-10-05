package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JetStreamServerConfigTest {

    @Test
    fun `JetStreamServerConfig defaults to null config and stats`() {
        val serverConfig = JetStreamServerConfig()
        assertNull(serverConfig.config)
        assertNull(serverConfig.stats)
    }

    @Test
    fun `JetStreamServerConfig with config and stats`() {
        val config = JetStreamConfig(maxMemory = 1024, maxStorage = 2048, storeDir = "/tmp")
        val stats = JetStreamStats(memory = 512, storage = 1024, accounts = 2)
        val serverConfig = JetStreamServerConfig(config = config, stats = stats)
        assertEquals(config, serverConfig.config)
        assertEquals(stats, serverConfig.stats)
    }

    @Test
    fun `JetStreamConfig has sensible defaults`() {
        val config = JetStreamConfig()
        assertEquals(0, config.maxMemory)
        assertEquals(0, config.maxStorage)
        assertEquals("", config.storeDir)
    }

    @Test
    fun `JetStreamStats has sensible defaults`() {
        val stats = JetStreamStats()
        assertEquals(0, stats.memory)
        assertEquals(0, stats.storage)
        assertEquals(0, stats.reservedMemory)
        assertEquals(0, stats.reservedStorage)
        assertEquals(0, stats.accounts)
        assertEquals(0, stats.haAssets)
        assertNull(stats.api)
    }

    @Test
    fun `JetStreamStats with api stats`() {
        val api = JetStreamApiStats(total = 100, errors = 5)
        val stats = JetStreamStats(api = api)
        assertEquals(100, stats.api?.total)
        assertEquals(5, stats.api?.errors)
    }

    @Test
    fun `JetStreamApiStats defaults`() {
        val api = JetStreamApiStats()
        assertEquals(0, api.total)
        assertEquals(0, api.errors)
    }

    @Test
    fun `JetStreamApiStats equality`() {
        val a = JetStreamApiStats(total = 10, errors = 1)
        val b = JetStreamApiStats(total = 10, errors = 1)
        assertEquals(a, b)
    }
}

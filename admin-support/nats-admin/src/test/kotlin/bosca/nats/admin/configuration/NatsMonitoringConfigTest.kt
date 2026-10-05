package bosca.nats.admin.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class NatsMonitoringConfigTest {

    @Test
    fun `NatsMonitoringConfig has default monitoring URL`() {
        val config = NatsMonitoringConfig()
        assertEquals("http://localhost:8222", config.monitoringUrl)
    }

    @Test
    fun `NatsMonitoringConfig accepts custom URL`() {
        val config = NatsMonitoringConfig(monitoringUrl = "http://nats-server:8222")
        assertEquals("http://nats-server:8222", config.monitoringUrl)
    }

    @Test
    fun `NatsMonitoringConfig equality`() {
        val a = NatsMonitoringConfig(monitoringUrl = "http://a:8222")
        val b = NatsMonitoringConfig(monitoringUrl = "http://a:8222")
        assertEquals(a, b)
    }

    @Test
    fun `NatsMonitoringConfig copy with modification`() {
        val original = NatsMonitoringConfig()
        val modified = original.copy(monitoringUrl = "http://custom:9222")
        assertEquals("http://custom:9222", modified.monitoringUrl)
    }
}

package bosca.telemetry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitoringModuleTest {

    @Test
    fun `health check recognition covers every probe and ordinary routes`() {
        assertTrue(isHealthCheck("/api/v1/live"))
        assertTrue(isHealthCheck("/api/v1/health"))
        assertTrue(isHealthCheck("/api/v1/ready"))
        assertFalse(isHealthCheck("/api/v1/content"))
        assertFalse(isHealthCheck("/api/v1/health/extended"))
    }
}

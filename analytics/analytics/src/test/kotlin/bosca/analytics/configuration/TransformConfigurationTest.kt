package bosca.analytics.configuration

import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.transform.AnalyticsScriptTransform
import bosca.analytics.transform.ErrorFingerprintTransform
import bosca.analytics.transform.SessionHeartbeatTransform
import bosca.analytics.transform.geo.CloudflareGeoTransform
import bosca.counter.Counter
import bosca.di.ObjectProvider
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.SecurityService
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransformConfigurationTest {

    private val config = TransformConfiguration()

    private fun buildTransforms(): EventPipelineTransforms = config.transforms(
        config.cloudflare(),
        mockk<ObjectProvider<AnalyticsScriptBindingService>>(relaxed = true),
        mockk<ObjectProvider<ScriptService>>(relaxed = true),
        mockk<ObjectProvider<ScriptExecutionService>>(relaxed = true),
        mockk<ObjectProvider<SecurityService>>(relaxed = true),
        Json,
        EventProcessingConfiguration(),
        mockk<Counter>(relaxed = true),
        mockk<ObjectProvider<LiveSessionsService>>(relaxed = true),
    )

    @Test
    fun `cloudflare creates a CloudflareGeoTransform`() {
        assertTrue(config.cloudflare() is CloudflareGeoTransform)
    }

    @Test
    fun `transforms pipeline starts with geo transform`() {
        val pipeline = buildTransforms()
        assertTrue(pipeline.transforms.isNotEmpty())
        assertTrue(pipeline.transforms[0] is CloudflareGeoTransform)
    }

    @Test
    fun `transforms pipeline has correct number of transforms`() {
        val pipeline = buildTransforms()
        // geo + session heartbeat (publish + count + strip) + error fingerprint + direct script bindings + batch transform + batch trigger
        val expected = 6
        assertEquals(expected, pipeline.transforms.size)
        assertTrue(pipeline.transforms[1] is SessionHeartbeatTransform)
        assertTrue(pipeline.transforms[2] is ErrorFingerprintTransform)
        assertTrue(pipeline.transforms[3] is AnalyticsScriptTransform)
    }

    @Test
    fun `EventPipelineTransforms wraps transform list`() {
        val pipeline = EventPipelineTransforms(emptyList())
        assertTrue(pipeline.transforms.isEmpty())
    }
}

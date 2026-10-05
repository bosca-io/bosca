package bosca.analytics.transform.geo

import bosca.analytics.model.Device
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.Geo
import bosca.server.Headers
import bosca.server.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudflareGeoTransformTest {

    private val transform = CloudflareGeoTransform()

    private fun createEvents(geo: Geo? = null): Events {
        val context = EventContext(
            appId = "test-app",
            appVersion = "1.0.0",
            device = Device(
                installationId = "inst-1",
                manufacturer = "TestMfg",
                model = "TestModel",
                platform = "test",
                primaryLocale = "en-US",
                systemName = "TestOS",
                timezone = "UTC",
                type = "phone",
                version = "1.0"
            ),
            sessionId = "sess-1",
            geo = geo
        )
        return Events(
            context = context,
            events = listOf(
                Event(
                    created = 1000L,
                    createdMicros = 0,
                    type = EventType.Session,
                    element = null,
                    clientId = null
                )
            ),
            sent = 1000L,
            sentMicros = 0
        )
    }

    private fun contextWithHeaders(vararg headers: Pair<String, String>): EventPipelineContext {
        val headerPairs = headers.map { it.first to listOf(it.second) }
        return EventPipelineContext(headersOf(*headerPairs.toTypedArray()))
    }

    @Test
    fun `transform populates geo from cloudflare headers`() = runTest {
        val ctx = contextWithHeaders(
            "cf-ipcity" to "San Francisco",
            "cf-ipcountry" to "US",
            "cf-ipcontinent" to "NA",
            "cf-region" to "California",
            "cf-region-code" to "CA",
            "cf-postal-code" to "94102",
            "cf-timezone" to "America/Los_Angeles",
            "cf-iplongitude" to "-122.4194",
            "cf-iplatitude" to "37.7749"
        )
        val events = createEvents()
        val result = transform.transform(ctx, events)

        val geo = result.context?.geo
        assertEquals("San Francisco", geo?.city)
        assertEquals("US", geo?.country)
        assertEquals("NA", geo?.continent)
        assertEquals("California", geo?.region)
        assertEquals("CA", geo?.regionCode)
        assertEquals("94102", geo?.postalCode)
        assertEquals("America/Los_Angeles", geo?.timezone)
        assertEquals(-122.4194, geo?.longitude)
        assertEquals(37.7749, geo?.latitude)
    }

    @Test
    fun `transform preserves existing geo fields when headers are absent`() = runTest {
        val existingGeo = Geo(city = "Existing City", country = "XX")
        val ctx = contextWithHeaders()
        val events = createEvents(existingGeo)
        val result = transform.transform(ctx, events)

        val geo = result.context?.geo
        // Absent headers must leave already-enriched values intact, not wipe them.
        assertEquals("Existing City", geo?.city)
        assertEquals("XX", geo?.country)
    }

    @Test
    fun `transform is a no-op when re-run against empty headers on enriched events`() = runTest {
        // Mirrors the processor / runner path: the collector enriched geo and
        // published to NATS; the consumer re-runs the chain with empty headers.
        val enrichedGeo = Geo(
            city = "San Francisco",
            country = "US",
            continent = "NA",
            region = "California",
            regionCode = "CA",
            postalCode = "94102",
            timezone = "America/Los_Angeles",
            longitude = -122.4194,
            latitude = 37.7749
        )
        val ctx = EventPipelineContext(Headers.Empty)
        val events = createEvents(enrichedGeo)
        val result = transform.transform(ctx, events)

        assertEquals(enrichedGeo, result.context?.geo)
    }

    @Test
    fun `transform handles invalid longitude and latitude gracefully`() = runTest {
        val ctx = contextWithHeaders(
            "cf-iplongitude" to "not-a-number",
            "cf-iplatitude" to "also-not"
        )
        val events = createEvents()
        val result = transform.transform(ctx, events)

        // Unparseable coordinates must not crash and must not be applied.
        val geo = result.context?.geo
        assertNull(geo?.longitude)
        assertNull(geo?.latitude)
    }

    @Test
    fun `transform leaves existing coordinates intact when headers are unparseable`() = runTest {
        val existingGeo = Geo(longitude = -122.4194, latitude = 37.7749)
        val ctx = contextWithHeaders(
            "cf-iplongitude" to "not-a-number",
            "cf-iplatitude" to "also-not"
        )
        val events = createEvents(existingGeo)
        val result = transform.transform(ctx, events)

        val geo = result.context?.geo
        assertEquals(-122.4194, geo?.longitude)
        assertEquals(37.7749, geo?.latitude)
    }

    @Test
    fun `transform returns events unchanged when context is null`() = runTest {
        val ctx = contextWithHeaders("cf-ipcity" to "SF")
        val events = Events(
            context = null,
            events = emptyList(),
            sent = 0L,
            sentMicros = 0
        )
        val result = transform.transform(ctx, events)
        assertNull(result.context)
    }

    @Test
    fun `GeoPipelineTransform extends EventPipelineTransform`() {
        assertTrue(transform is bosca.analytics.transform.EventPipelineTransform)
    }

    private fun assertTrue(condition: Boolean) {
        kotlin.test.assertTrue(condition)
    }
}

package bosca.analytics.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsModelTest {

    // --- AnalyticsQuery ---

    @Test
    fun `AnalyticsQuery defaults have correct permission flags`() {
        val query = AnalyticsQuery(
            key = "test-query",
            name = "Test Query",
            description = "A test query",
            query = "SELECT 1"
        )
        assertFalse(query.public)
        assertFalse(query.publicContent)
        assertFalse(query.publicList)
        assertFalse(query.publicSupplementary)
        assertTrue(query.isPublished)
        assertFalse(query.isAdvertised)
        assertFalse(query.isDeleted)
    }

    @Test
    fun `AnalyticsQuery default id is NIL`() {
        val query = AnalyticsQuery(
            key = "k",
            name = "n",
            description = "d",
            query = "q"
        )
        assertEquals(Uuid.NIL, query.id)
    }

    // --- AnalyticsDashboard ---

    @Test
    fun `AnalyticsDashboard defaults have correct permission flags`() {
        val dashboard = AnalyticsDashboard(
            key = "test-dashboard",
            name = "Test Dashboard",
            description = "A test dashboard",
            configuration = JsonObject(emptyMap())
        )
        assertFalse(dashboard.public)
        assertFalse(dashboard.publicContent)
        assertFalse(dashboard.publicList)
        assertFalse(dashboard.publicSupplementary)
        assertTrue(dashboard.isPublished)
        assertFalse(dashboard.isAdvertised)
        assertFalse(dashboard.isDeleted)
    }

    @Test
    fun `AnalyticsDashboard parameters default to null`() {
        val dashboard = AnalyticsDashboard(
            key = "k",
            name = "n",
            description = "d",
            configuration = JsonObject(emptyMap())
        )
        assertEquals(null, dashboard.parameters)
    }

    // --- AnalyticsVisualization ---

    @Test
    fun `AnalyticsVisualization defaults have correct permission flags`() {
        val vis = AnalyticsVisualization(
            key = "test-vis",
            name = "Test Visualization",
            description = "A test vis",
            type = AnalyticsVisualizationType.BAR,
            configuration = JsonObject(emptyMap())
        )
        assertFalse(vis.public)
        assertFalse(vis.publicContent)
        assertFalse(vis.publicList)
        assertFalse(vis.publicSupplementary)
        assertTrue(vis.isPublished)
        assertFalse(vis.isAdvertised)
        assertFalse(vis.isDeleted)
    }

    @Test
    fun `AnalyticsVisualization queryId defaults to null`() {
        val vis = AnalyticsVisualization(
            key = "k",
            name = "n",
            description = "d",
            type = AnalyticsVisualizationType.TABLE,
            configuration = JsonObject(emptyMap())
        )
        assertEquals(null, vis.queryId)
    }

    // --- EventPipelineContext ---

    @Test
    fun `EventPipelineContext returns header value`() {
        val headers = bosca.server.Headers.build { append("X-Custom-Header", "custom-value") }
        val context = EventPipelineContext(headers)
        assertEquals("custom-value", context.getHeaderValue("X-Custom-Header"))
    }

    @Test
    fun `EventPipelineContext returns null for missing header`() {
        val headers = bosca.server.Headers.build { }
        val context = EventPipelineContext(headers)
        assertEquals(null, context.getHeaderValue("X-Missing"))
    }

    // --- AnalyticsQueryResponse ---

    @Test
    fun `AnalyticsQueryResponse holds records`() {
        val records = listOf(
            JsonObject(mapOf("id" to JsonPrimitive(1))),
            JsonObject(mapOf("id" to JsonPrimitive(2)))
        )
        val response = AnalyticsQueryResponse(records)
        assertEquals(2, response.records.size)
        assertFalse(response.stale)
    }

    @Test
    fun `AnalyticsQueryResponse serializes explicit stale freshness`() {
        val refreshedAt = OffsetDateTime.parse("2026-07-28T12:00:00Z")
        val response = AnalyticsQueryResponse(
            records = emptyList(),
            cached = true,
            refreshedAt = refreshedAt,
            stale = true,
        )

        val encoded = Json.encodeToString(AnalyticsQueryResponse.serializer(), response)
        val decoded = Json.decodeFromString(AnalyticsQueryResponse.serializer(), encoded)

        assertTrue(decoded.cached)
        assertTrue(decoded.stale)
        assertEquals(refreshedAt, decoded.refreshedAt)
    }
}

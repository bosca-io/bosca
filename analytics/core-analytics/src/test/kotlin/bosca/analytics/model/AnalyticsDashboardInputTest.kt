package bosca.analytics.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class AnalyticsDashboardInputTest {

    private val config = JsonObject(emptyMap())

    @Test
    fun `AnalyticsDashboardInput id defaults to NIL`() {
        val input = AnalyticsDashboardInput(
            key = "k", name = "n", description = "d",
            configuration = config, visualizations = emptyList()
        )
        assertEquals(Uuid.NIL, input.id)
    }

    @Test
    fun `AnalyticsDashboardInput parameters defaults to empty list`() {
        val input = AnalyticsDashboardInput(
            key = "k", name = "n", description = "d",
            configuration = config, visualizations = emptyList()
        )
        assertEquals(emptyList(), input.parameters)
    }

    @Test
    fun `AnalyticsDashboardInput stores all fields`() {
        val id = Uuid.random()
        val visId = Uuid.random()
        val vis = AnalyticsVisualizationInstanceInput(visualizationId = visId, configuration = config)
        val input = AnalyticsDashboardInput(
            id = id,
            key = "dashboard-1",
            name = "Dashboard One",
            description = "First dashboard",
            configuration = config,
            visualizations = listOf(vis)
        )
        assertEquals(id, input.id)
        assertEquals("dashboard-1", input.key)
        assertEquals("Dashboard One", input.name)
        assertEquals("First dashboard", input.description)
        assertEquals(1, input.visualizations.size)
    }

    @Test
    fun `AnalyticsDashboardInput equality`() {
        val id = Uuid.random()
        val a = AnalyticsDashboardInput(id = id, key = "k", name = "n", description = "d", configuration = config, visualizations = emptyList())
        val b = AnalyticsDashboardInput(id = id, key = "k", name = "n", description = "d", configuration = config, visualizations = emptyList())
        assertEquals(a, b)
    }

    @Test
    fun `AnalyticsDashboardInput inequality on different key`() {
        val a = AnalyticsDashboardInput(key = "a", name = "n", description = "d", configuration = config, visualizations = emptyList())
        val b = AnalyticsDashboardInput(key = "b", name = "n", description = "d", configuration = config, visualizations = emptyList())
        assertNotEquals(a, b)
    }

    // --- AnalyticsVisualizationInstanceInput ---

    @Test
    fun `AnalyticsVisualizationInstanceInput stores visualizationId`() {
        val visId = Uuid.random()
        val input = AnalyticsVisualizationInstanceInput(visualizationId = visId, configuration = config)
        assertEquals(visId, input.visualizationId)
    }

    @Test
    fun `AnalyticsVisualizationInstanceInput stores configuration`() {
        val config = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val input = AnalyticsVisualizationInstanceInput(visualizationId = Uuid.random(), configuration = config)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `AnalyticsVisualizationInstanceInput equality`() {
        val visId = Uuid.random()
        val a = AnalyticsVisualizationInstanceInput(visualizationId = visId, configuration = config)
        val b = AnalyticsVisualizationInstanceInput(visualizationId = visId, configuration = config)
        assertEquals(a, b)
    }

    @Test
    fun `AnalyticsVisualizationInstanceInput inequality`() {
        val a = AnalyticsVisualizationInstanceInput(visualizationId = Uuid.random(), configuration = config)
        val b = AnalyticsVisualizationInstanceInput(visualizationId = Uuid.random(), configuration = config)
        assertNotEquals(a, b)
    }
}

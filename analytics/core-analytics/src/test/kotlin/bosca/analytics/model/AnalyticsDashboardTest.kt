package bosca.analytics.model

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AnalyticsDashboardTest {

    private val config = JsonObject(emptyMap())

    // --- AnalyticsDashboard ---

    @Test
    fun `AnalyticsDashboard id defaults to NIL`() {
        val dashboard = AnalyticsDashboard(key = "k", name = "n", description = "d", configuration = config)
        assertEquals(Uuid.NIL, dashboard.id)
    }

    @Test
    fun `AnalyticsDashboard parameters defaults to null`() {
        val dashboard = AnalyticsDashboard(key = "k", name = "n", description = "d", configuration = config)
        assertEquals(null, dashboard.parameters)
    }

    @Test
    fun `AnalyticsDashboard permission flags are correct`() {
        val dashboard = AnalyticsDashboard(key = "k", name = "n", description = "d", configuration = config)
        assertFalse(dashboard.public)
        assertFalse(dashboard.publicContent)
        assertFalse(dashboard.publicList)
        assertFalse(dashboard.publicSupplementary)
        assertTrue(dashboard.isPublished)
        assertFalse(dashboard.isAdvertised)
        assertFalse(dashboard.isDeleted)
    }

    // --- AnalyticsDashboardInput ---

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
    fun `AnalyticsDashboardInput stores visualizations`() {
        val visId = Uuid.random()
        val vis = AnalyticsVisualizationInstanceInput(visualizationId = visId, configuration = config)
        val input = AnalyticsDashboardInput(
            key = "k", name = "n", description = "d",
            configuration = config, visualizations = listOf(vis)
        )
        assertEquals(1, input.visualizations.size)
        assertEquals(visId, input.visualizations[0].visualizationId)
    }

    // --- AnalyticsDashboardParameter ---

    @Test
    fun `AnalyticsDashboardParameter defaults`() {
        val param = AnalyticsDashboardParameter(
            parameter = "date_range", name = "Date Range",
            description = "Filter by date", type = QueryParameterType.DATE, arrayType = null
        )
        assertEquals(null, param.defaultValue)
        assertFalse(param.required)
    }

    @Test
    fun `AnalyticsDashboardParameter stores all properties`() {
        val param = AnalyticsDashboardParameter(
            parameter = "ids", name = "IDs",
            description = "Item IDs", type = QueryParameterType.ARRAY,
            arrayType = QueryParameterType.STRING, required = true
        )
        assertEquals("ids", param.parameter)
        assertEquals("IDs", param.name)
        assertEquals(QueryParameterType.ARRAY, param.type)
        assertEquals(QueryParameterType.STRING, param.arrayType)
        assertTrue(param.required)
    }
}

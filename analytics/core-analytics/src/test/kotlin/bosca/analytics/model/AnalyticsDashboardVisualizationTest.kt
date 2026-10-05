package bosca.analytics.model

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class AnalyticsDashboardVisualizationTest {

    @Test
    fun `AnalyticsDashboardVisualization stores all properties`() {
        val id = Uuid.random()
        val dashboardId = Uuid.random()
        val visualizationId = Uuid.random()
        val config = JsonObject(emptyMap())
        val dv = AnalyticsDashboardVisualization(
            id = id, dashboardId = dashboardId,
            visualizationId = visualizationId, configuration = config
        )
        assertEquals(id, dv.id)
        assertEquals(dashboardId, dv.dashboardId)
        assertEquals(visualizationId, dv.visualizationId)
        assertEquals(config, dv.configuration)
    }
}

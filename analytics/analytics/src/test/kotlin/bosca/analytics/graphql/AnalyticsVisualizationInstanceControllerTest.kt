package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInstance
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsVisualizationInstanceControllerTest {

    private val controller = AnalyticsVisualizationInstanceController()

    private val vizId = UUID.random()
    private val instanceId = UUID.random()
    private val vizConfig = JsonObject(mapOf("color" to JsonPrimitive("blue")))
    private val instanceConfig = JsonObject(mapOf("width" to JsonPrimitive(600)))

    private val visualization = AnalyticsVisualization(
        id = vizId,
        key = "chart",
        name = "Chart",
        description = "A chart",
        type = AnalyticsVisualizationType.BAR,
        configuration = vizConfig
    )

    private val instance = AnalyticsVisualizationInstance(
        id = instanceId,
        configuration = instanceConfig,
        visualization = visualization
    )

    @Test
    fun `id delegates to model field`() {
        assertEquals(instanceId, controller.id(instance))
    }

    @Test
    fun `configuration delegates to model field`() {
        assertEquals(instanceConfig, controller.configuration(instance))
    }

    @Test
    fun `visualization delegates to model field`() {
        assertEquals(visualization, controller.visualization(instance))
        assertEquals(vizId, controller.visualization(instance).id)
    }
}

package bosca.analytics.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class AnalyticsVisualizationInstanceTest {

    private val config = JsonObject(mapOf("color" to JsonPrimitive("blue")))
    private val emptyConfig = JsonObject(emptyMap())

    private fun makeVisualization() = AnalyticsVisualization(
        key = "vis-key",
        name = "Test Vis",
        description = "A visualization",
        type = AnalyticsVisualizationType.BAR,
        configuration = emptyConfig
    )

    @Test
    fun `AnalyticsVisualizationInstance stores id`() {
        val id = Uuid.random()
        val instance = AnalyticsVisualizationInstance(
            id = id,
            configuration = config,
            visualization = makeVisualization()
        )
        assertEquals(id, instance.id)
    }

    @Test
    fun `AnalyticsVisualizationInstance stores configuration`() {
        val instance = AnalyticsVisualizationInstance(
            id = Uuid.random(),
            configuration = config,
            visualization = makeVisualization()
        )
        assertEquals(config, instance.configuration)
    }

    @Test
    fun `AnalyticsVisualizationInstance stores visualization`() {
        val vis = makeVisualization()
        val instance = AnalyticsVisualizationInstance(
            id = Uuid.random(),
            configuration = emptyConfig,
            visualization = vis
        )
        assertEquals("vis-key", instance.visualization.key)
        assertEquals(AnalyticsVisualizationType.BAR, instance.visualization.type)
    }

    @Test
    fun `AnalyticsVisualizationInstance equality`() {
        val id = Uuid.random()
        val vis = makeVisualization()
        val a = AnalyticsVisualizationInstance(id, config, vis)
        val b = AnalyticsVisualizationInstance(id, config, vis)
        assertEquals(a, b)
    }

    @Test
    fun `AnalyticsVisualizationInstance inequality on different id`() {
        val vis = makeVisualization()
        val a = AnalyticsVisualizationInstance(Uuid.random(), config, vis)
        val b = AnalyticsVisualizationInstance(Uuid.random(), config, vis)
        assertNotEquals(a, b)
    }

    @Test
    fun `AnalyticsVisualizationInstance copy with new configuration`() {
        val id = Uuid.random()
        val vis = makeVisualization()
        val original = AnalyticsVisualizationInstance(id, config, vis)
        val newConfig = JsonObject(mapOf("color" to JsonPrimitive("red")))
        val copied = original.copy(configuration = newConfig)
        assertEquals(newConfig, copied.configuration)
        assertEquals(id, copied.id)
    }
}

package bosca.analytics.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AnalyticsVisualizationTest {

    @Test
    fun `AnalyticsVisualization id defaults to NIL`() {
        val config = JsonObject(mapOf("chart" to JsonPrimitive("bar")))
        val viz = AnalyticsVisualization(
            key = "user-chart",
            name = "User Chart",
            description = "User activity chart",
            type = AnalyticsVisualizationType.TABLE,
            configuration = config
        )
        assertEquals(Uuid.NIL, viz.id)
        assertEquals("user-chart", viz.key)
        assertNull(viz.queryId)
    }

    @Test
    fun `AnalyticsVisualization permission flags`() {
        val viz = AnalyticsVisualization(
            key = "test",
            name = "Test",
            description = "Test",
            type = AnalyticsVisualizationType.TABLE,
            configuration = JsonObject(emptyMap())
        )
        assertFalse(viz.public)
        assertFalse(viz.publicContent)
        assertFalse(viz.publicList)
        assertFalse(viz.publicSupplementary)
        assertTrue(viz.isPublished)
        assertFalse(viz.isAdvertised)
        assertFalse(viz.isDeleted)
    }

    @Test
    fun `AnalyticsVisualizationInstance stores all properties`() {
        val id = Uuid.random()
        val config = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val viz = AnalyticsVisualization(
            key = "test",
            name = "Test",
            description = "Test",
            type = AnalyticsVisualizationType.TABLE,
            configuration = config
        )
        val instance = AnalyticsVisualizationInstance(
            id = id,
            configuration = config,
            visualization = viz
        )
        assertEquals(id, instance.id)
        assertEquals(config, instance.configuration)
        assertEquals(viz, instance.visualization)
    }

    @Test
    fun `AnalyticsQueryResponse stores records`() {
        val records = listOf(
            JsonPrimitive("record1"),
            JsonPrimitive("record2")
        )
        val response = AnalyticsQueryResponse(records = records)
        assertEquals(2, response.records.size)
    }
}

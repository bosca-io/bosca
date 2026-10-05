package bosca.analytics.model

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AnalyticsVisualizationInputTest {

    private val config = JsonObject(emptyMap())

    @Test
    fun `AnalyticsVisualizationInput id defaults to NIL`() {
        val input = AnalyticsVisualizationInput(
            key = "k", name = "n", description = "d",
            type = AnalyticsVisualizationType.BAR, configuration = config
        )
        assertEquals(Uuid.NIL, input.id)
    }

    @Test
    fun `AnalyticsVisualizationInput queryId defaults to null`() {
        val input = AnalyticsVisualizationInput(
            key = "k", name = "n", description = "d",
            type = AnalyticsVisualizationType.TABLE, configuration = config
        )
        assertNull(input.queryId)
    }

    @Test
    fun `AnalyticsVisualizationInput stores all properties`() {
        val queryId = Uuid.random()
        val input = AnalyticsVisualizationInput(
            key = "chart", name = "Chart", description = "A chart",
            queryId = queryId, type = AnalyticsVisualizationType.LINE, configuration = config
        )
        assertEquals("chart", input.key)
        assertEquals(queryId, input.queryId)
        assertEquals(AnalyticsVisualizationType.LINE, input.type)
    }
}

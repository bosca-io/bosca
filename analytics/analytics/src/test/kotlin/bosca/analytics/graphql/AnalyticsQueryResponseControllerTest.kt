package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsQueryResponseControllerTest {

    private val controller = AnalyticsQueryResponseController()

    @Test
    fun `records returns the list from model`() {
        val records = listOf(
            JsonObject(mapOf("date" to JsonPrimitive("2024-01-01"), "value" to JsonPrimitive(42))),
            JsonObject(mapOf("date" to JsonPrimitive("2024-01-02"), "value" to JsonPrimitive(55)))
        )
        val response = AnalyticsQueryResponse(records = records)
        val result = controller.records(response)
        assertEquals(2, result.size)
    }

    @Test
    fun `records returns empty list when no records`() {
        val response = AnalyticsQueryResponse(records = emptyList())
        assertTrue(controller.records(response).isEmpty())
    }

    @Test
    fun `freshness fields return cache metadata`() {
        val response = AnalyticsQueryResponse(
            records = emptyList(),
            cached = true,
            refreshedAt = null,
            stale = true,
        )

        assertTrue(controller.cached(response))
        assertTrue(controller.stale(response))
        assertEquals(null, controller.refreshedAt(response))
    }
}

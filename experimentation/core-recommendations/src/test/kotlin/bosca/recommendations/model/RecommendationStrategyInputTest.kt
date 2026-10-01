@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationStrategyInputTest {

    @Test
    fun `stores required fields`() {
        val input = RecommendationStrategyInput(
            name = "Trending 24h",
            type = RecommendationStrategyType.TRENDING
        )
        assertEquals("Trending 24h", input.name)
        assertEquals(RecommendationStrategyType.TRENDING, input.type)
    }

    @Test
    fun `has sensible defaults`() {
        val input = RecommendationStrategyInput(name = "test", type = RecommendationStrategyType.PERSONALIZED)
        assertEquals("", input.description)
        assertEquals(RecommendationStrategyStatus.DRAFT, input.status)
        assertNull(input.analyticsQueryId)
        assertNull(input.configuration)
        assertEquals(0, input.priority)
        assertEquals(10, input.maxRecommendations)
        assertNull(input.evaluationSchedule)
    }

    @Test
    fun `stores all fields`() {
        val queryId = UUID.random()
        val config = JsonObject(mapOf("lookback_days" to JsonPrimitive(7)))
        val input = RecommendationStrategyInput(
            name = "Related Content",
            description = "Item-to-item co-occurrence",
            type = RecommendationStrategyType.CO_ENGAGEMENT,
            status = RecommendationStrategyStatus.ACTIVE,
            analyticsQueryId = queryId,
            configuration = config,
            priority = 5,
            maxRecommendations = 20,
            evaluationSchedule = "0 */6 * * *"
        )
        assertEquals("Item-to-item co-occurrence", input.description)
        assertEquals(RecommendationStrategyStatus.ACTIVE, input.status)
        assertEquals(queryId, input.analyticsQueryId)
        assertEquals(config, input.configuration)
        assertEquals(5, input.priority)
        assertEquals(20, input.maxRecommendations)
        assertEquals("0 */6 * * *", input.evaluationSchedule)
    }

    @Test
    fun `equality`() {
        val a = RecommendationStrategyInput(name = "s", type = RecommendationStrategyType.TRENDING)
        val b = RecommendationStrategyInput(name = "s", type = RecommendationStrategyType.TRENDING)
        assertEquals(a, b)
    }
}

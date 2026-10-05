@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationStrategyTest {

    @Test
    fun `has sensible defaults`() {
        val strategy = RecommendationStrategy(
            name = "Trending",
            type = RecommendationStrategyType.TRENDING
        )
        assertEquals(UUID.NIL, strategy.id)
        assertEquals("", strategy.description)
        assertEquals(RecommendationStrategyStatus.DRAFT, strategy.status)
        assertNull(strategy.analyticsQueryId)
        assertNull(strategy.configuration)
        assertEquals(0, strategy.priority)
        assertEquals(10, strategy.maxRecommendations)
        assertNull(strategy.scheduledJobId)
        assertNull(strategy.lastEvaluated)
    }

    @Test
    fun `stores all fields`() {
        val id = UUID.random()
        val strategy = RecommendationStrategy(
            id = id,
            name = "Trending",
            description = "Recommend trending content",
            type = RecommendationStrategyType.TRENDING,
            status = RecommendationStrategyStatus.ACTIVE,
            priority = 10,
            maxRecommendations = 25,
        )
        assertEquals(id, strategy.id)
        assertEquals("Trending", strategy.name)
        assertEquals(RecommendationStrategyType.TRENDING, strategy.type)
        assertEquals(RecommendationStrategyStatus.ACTIVE, strategy.status)
        assertEquals(10, strategy.priority)
        assertEquals(25, strategy.maxRecommendations)
    }
}

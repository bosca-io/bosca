@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

class RecommendationPlacementStrategyTest {

    @Test
    fun `stores required fields`() {
        val placementId = UUID.random()
        val strategyId = UUID.random()
        val ps = RecommendationPlacementStrategy(
            placementId = placementId,
            strategyId = strategyId,
        )
        assertEquals(placementId, ps.placementId)
        assertEquals(strategyId, ps.strategyId)
        assertEquals(0, ps.priority)
    }

    @Test
    fun `stores all fields with custom priority`() {
        val placementId = UUID.random()
        val strategyId = UUID.random()
        val ps = RecommendationPlacementStrategy(
            placementId = placementId,
            strategyId = strategyId,
            priority = 10,
        )
        assertEquals(placementId, ps.placementId)
        assertEquals(strategyId, ps.strategyId)
        assertEquals(10, ps.priority)
    }

    @Test
    fun `default priority is zero`() {
        val ps = RecommendationPlacementStrategy(
            placementId = UUID.random(),
            strategyId = UUID.random(),
        )
        assertEquals(0, ps.priority)
    }

    @Test
    fun `equality`() {
        val placementId = UUID.random()
        val strategyId = UUID.random()
        val a = RecommendationPlacementStrategy(placementId = placementId, strategyId = strategyId, priority = 5)
        val b = RecommendationPlacementStrategy(placementId = placementId, strategyId = strategyId, priority = 5)
        assertEquals(a, b)
    }
}

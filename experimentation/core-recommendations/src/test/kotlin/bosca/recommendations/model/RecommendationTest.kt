@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationTest {

    @Test
    fun `has sensible defaults`() {
        val metadataId = UUID.random()
        val strategyId = UUID.random()
        val rec = Recommendation(
            metadataId = metadataId,
            strategyId = strategyId,
        )
        assertEquals(UUID.NIL, rec.id)
        assertEquals(metadataId, rec.metadataId)
        assertEquals(strategyId, rec.strategyId)
        assertEquals(0.0, rec.score)
        assertNull(rec.reason)
        assertNull(rec.context)
        assertNull(rec.expiresAt)
        assertEquals(emptySet(), rec.sources)
    }

    @Test
    fun `stores score and reason`() {
        val sources = setOf(
            RecommendationSource.CONTENT_MODEL,
            RecommendationSource.PERSONALIZED_MODEL,
        )
        val rec = Recommendation(
            metadataId = UUID.random(),
            strategyId = UUID.random(),
            score = 0.95,
            reason = "Based on your interest in category X",
            sources = sources,
        )
        assertEquals(0.95, rec.score)
        assertEquals("Based on your interest in category X", rec.reason)
        assertEquals(sources, rec.sources)
    }
}

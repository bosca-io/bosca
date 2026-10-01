@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationPlacementTest {

    @Test
    fun `has sensible defaults`() {
        val placement = RecommendationPlacement(
            name = "Home Feed",
            slug = "home_feed"
        )
        assertEquals(UUID.NIL, placement.id)
        assertEquals("Home Feed", placement.name)
        assertEquals("", placement.description)
        assertEquals("home_feed", placement.slug)
        assertEquals(5, placement.maxItems)
        assertNull(placement.configuration)
    }

    @Test
    fun `input stores all fields`() {
        val input = RecommendationPlacementInput(
            name = "Article Sidebar",
            description = "Related content sidebar",
            slug = "article_sidebar",
            maxItems = 3,
        )
        assertEquals("Article Sidebar", input.name)
        assertEquals("Related content sidebar", input.description)
        assertEquals("article_sidebar", input.slug)
        assertEquals(3, input.maxItems)
    }
}

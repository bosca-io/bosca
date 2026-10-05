package bosca.recommendations.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a recommendation placement,
 * specifying the display location's name, URL-friendly slug,
 * maximum number of items to show, and optional configuration.
 */
@Serializable
data class RecommendationPlacementInput(
    val name: String,
    val description: String = "",
    val slug: String,
    val maxItems: Int = 5,
    @Contextual
    val configuration: JsonElement? = null,
)

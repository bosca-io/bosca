package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Defines a named location within the application where recommendations
 * are displayed, such as "home_feed", "article_sidebar", or "post_read_next".
 * Each placement is linked to one or more strategies that contribute
 * recommendations to that location, with configurable limits and
 * display parameters.
 */
@Serializable
data class RecommendationPlacement(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String = "",
    val slug: String,
    @ColumnName("max_items")
    val maxItems: Int = 5,
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
)

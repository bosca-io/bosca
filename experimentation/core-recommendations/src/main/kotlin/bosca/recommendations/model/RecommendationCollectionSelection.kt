package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** The currently eligible language representation selected for a recommended collection. */
@Serializable
data class RecommendationCollectionSelection(
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID,
    @ColumnName("language_tag")
    val languageTag: String,
)

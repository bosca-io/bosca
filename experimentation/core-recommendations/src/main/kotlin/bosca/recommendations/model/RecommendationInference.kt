package bosca.recommendations.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** The immutable inputs needed to explain a served model result after ranking, including cache reads. */
@Serializable
data class RecommendationInference(
    val modelVersion: Long,
    val personalized: Boolean,
    val contextType: String,
    val languageTag: String,
    @Contextual val profileId: UUID? = null,
    @Contextual val sourceId: UUID? = null,
)

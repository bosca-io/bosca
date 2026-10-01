package bosca.recommendations.model

import kotlinx.serialization.Serializable

/** API input for creating or updating a saved recommendation context. */
@Serializable
data class RecommendationContextInput(
    val type: String,
    val name: String,
    val description: String = "",
    val contentFilter: RecommendationContentFilterInput = RecommendationContentFilterInput(),
    val weights: RecommendationWeightsInput = RecommendationWeightsInput(),
)

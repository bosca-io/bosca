package bosca.recommendations.model

import kotlinx.serialization.Serializable

/** Editable relative importance captured by the next context training revision. */
@Serializable
data class RecommendationWeightsInput(
    val similarity: RecommendationSimilarityWeightsInput = RecommendationSimilarityWeightsInput(),
    val typePreferences: List<RecommendationTypePreferenceInput> = emptyList(),
    val defaultTypePreference: Double = 0.5,
    val content: Double = 1.0,
    val coEngagement: Double = 1.0,
    val cohortCoEngagement: Double = 1.0,
    val learnedNeighbor: Double = 1.0,
    val personalization: Double = 1.0,
    val rating: Double = 1.0,
) {
    /** Validates and normalizes the API input for persistence. */
    fun toModel() = RecommendationWeights(
        similarity = similarity.toModel(),
        typePreferences = typePreferences.map { RecommendationTypePreference(it.type, it.weight) },
        defaultTypePreference = defaultTypePreference,
        content = content,
        coEngagement = coEngagement,
        cohortCoEngagement = cohortCoEngagement,
        learnedNeighbor = learnedNeighbor,
        personalization = personalization,
        rating = rating,
    ).normalized()
}

/** Editable competing content similarity inputs. */
@Serializable
data class RecommendationSimilarityWeightsInput(
    val semantic: Double = 0.2,
    val categories: Double = 0.2,
    val labels: Double = 0.2,
    val language: Double = 0.2,
    val mime: Double = 0.2,
    val type: Double = 0.2,
    val collections: Double = 0.2,
) {
    /** Converts similarity inputs without changing the administrator's relative scale. */
    fun toModel() = RecommendationSimilarityWeights(
        semantic = semantic,
        categories = categories,
        labels = labels,
        language = language,
        mime = mime,
        type = type,
        collections = collections,
    )
}

/** Editable preference for one editorial type. */
@Serializable
data class RecommendationTypePreferenceInput(val type: String, val weight: Double)

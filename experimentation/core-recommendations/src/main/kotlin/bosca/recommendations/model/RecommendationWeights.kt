package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Relative importance saved with a context and captured by its trained model.
 * Zero removes a direct contribution; eligibility is controlled only by the content filter.
 */
@Serializable
data class RecommendationWeights(
    val similarity: RecommendationSimilarityWeights = RecommendationSimilarityWeights(),
    val typePreferences: List<RecommendationTypePreference> = emptyList(),
    val defaultTypePreference: Double = 0.5,
    val content: Double = 1.0,
    val coEngagement: Double = 1.0,
    val cohortCoEngagement: Double = 1.0,
    val learnedNeighbor: Double = 1.0,
    val personalization: Double = 1.0,
    val rating: Double = 1.0,
) {
    /** Validates importance values and returns exact, normalized editorial-type keys. */
    fun normalized(): RecommendationWeights {
        similarity.validate()
        validateImportance("defaultTypePreference", defaultTypePreference)
        validateImportance("content", content)
        validateImportance("coEngagement", coEngagement)
        validateImportance("cohortCoEngagement", cohortCoEngagement)
        validateImportance("learnedNeighbor", learnedNeighbor)
        validateImportance("personalization", personalization)
        validateImportance("rating", rating)
        val preferences = typePreferences.map {
            val type = it.type.trim().lowercase()
            require(type.isNotEmpty()) { "Editorial type must not be blank" }
            validateImportance("Type preference for $type", it.weight)
            it.copy(type = type)
        }
        require(preferences.map { it.type }.distinct().size == preferences.size) {
            "Editorial type preferences must have distinct normalized types"
        }
        return copy(typePreferences = preferences)
    }
}

/** Competing similarity inputs are normalized by their sum in the exported model. */
@Serializable
data class RecommendationSimilarityWeights(
    val semantic: Double = 0.2,
    val categories: Double = 0.2,
    val labels: Double = 0.2,
    val language: Double = 0.2,
    val mime: Double = 0.2,
    val type: Double = 0.2,
    val collections: Double = 0.2,
) {
    /** Rejects nonfinite, out-of-range, and entirely disabled similarity groups. */
    fun validate() {
        validateImportance("Similarity semantic", semantic)
        validateImportance("Similarity categories", categories)
        validateImportance("Similarity labels", labels)
        validateImportance("Similarity language", language)
        validateImportance("Similarity mime", mime)
        validateImportance("Similarity type", type)
        validateImportance("Similarity collections", collections)
        require(semantic + categories + labels + language + mime + type + collections > 0.0) {
            "At least one similarity weight must be greater than zero"
        }
    }
}

/** Preference for an exact normalized metadata.attributes.type value, independent of MIME. */
@Serializable
data class RecommendationTypePreference(val type: String, val weight: Double)

private fun validateImportance(name: String, value: Double) {
    require(value.isFinite() && value in 0.0..1.0) { "$name must be finite and between 0 and 1" }
}

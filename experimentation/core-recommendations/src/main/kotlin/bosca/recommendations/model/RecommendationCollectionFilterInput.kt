package bosca.recommendations.model

import kotlinx.serialization.Serializable

/** API input for collection recommendation-context eligibility. */
@Serializable
data class RecommendationCollectionFilterInput(
    val includedTypes: List<String> = emptyList(),
    val excludedTypes: List<String> = emptyList(),
    val includedAttributeTypes: List<String> = emptyList(),
    val excludedAttributeTypes: List<String> = emptyList(),
) {
    /** Converts this API input to the persisted service model. */
    fun toModel() = RecommendationCollectionFilter(
        includedTypes = includedTypes,
        excludedTypes = excludedTypes,
        includedAttributeTypes = includedAttributeTypes,
        excludedAttributeTypes = excludedAttributeTypes,
    )
}

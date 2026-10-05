package bosca.recommendations.model

import kotlinx.serialization.Serializable

/** API input for metadata recommendation-context eligibility. */
@Serializable
data class RecommendationMetadataFilterInput(
    val includedContentTypePrefixes: List<String> = emptyList(),
    val excludedContentTypePrefixes: List<String> = RecommendationMetadataFilter.DEFAULT_EXCLUDED_CONTENT_TYPE_PREFIXES,
    val includedAttributeTypes: List<String> = emptyList(),
    val excludedAttributeTypes: List<String> = emptyList(),
) {
    /** Converts this API input to the persisted service model. */
    fun toModel() = RecommendationMetadataFilter(
        includedContentTypePrefixes = includedContentTypePrefixes,
        excludedContentTypePrefixes = excludedContentTypePrefixes,
        includedAttributeTypes = includedAttributeTypes,
        excludedAttributeTypes = excludedAttributeTypes,
    )
}

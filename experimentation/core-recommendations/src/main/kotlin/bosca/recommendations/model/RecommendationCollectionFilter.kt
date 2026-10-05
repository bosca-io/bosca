package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Configures collection context classification using canonical collection `type` and `attributes.type` values.
 *
 * Each facet is evaluated independently and both must match. Within a facet, a non-empty include-list is
 * a complete allow-list and takes precedence over its exclusion list. All comparisons are exact after
 * case-insensitive normalization.
 */
@Serializable
data class RecommendationCollectionFilter(
    val includedTypes: List<String> = emptyList(),
    val excludedTypes: List<String> = emptyList(),
    val includedAttributeTypes: List<String> = emptyList(),
    val excludedAttributeTypes: List<String> = emptyList(),
) {
    /** Matches the collection's canonical type and editorial type; non-empty include lists take precedence. */
    fun matches(type: String, attributeType: String?): Boolean =
        matchesFilter(type, includedTypes, excludedTypes) &&
            matchesFilter(attributeType, includedAttributeTypes, excludedAttributeTypes)

    companion object {
        /** An unrestricted collection filter. */
        val ALL = RecommendationCollectionFilter()
    }
}

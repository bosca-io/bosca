package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Configures metadata context classification using canonical `contentType` and `attributes.type` values.
 *
 * Each facet is evaluated independently and both must match. Within a facet, a non-empty include-list is
 * a complete allow-list and takes precedence over its exclusion list. Comparisons are case-insensitive;
 * content-type matching uses prefixes and ignores MIME parameters, while attribute types match exactly.
 */
@Serializable
data class RecommendationMetadataFilter(
    val includedContentTypePrefixes: List<String> = emptyList(),
    val excludedContentTypePrefixes: List<String> = DEFAULT_EXCLUDED_CONTENT_TYPE_PREFIXES,
    val includedAttributeTypes: List<String> = emptyList(),
    val excludedAttributeTypes: List<String> = emptyList(),
) {
    /** Returns canonical filter values for query parameters without changing the saved configuration. */
    fun normalized(): RecommendationMetadataFilter = copy(
        includedContentTypePrefixes = includedContentTypePrefixes.normalizedFilterValues(),
        excludedContentTypePrefixes = excludedContentTypePrefixes.normalizedFilterValues(),
        includedAttributeTypes = includedAttributeTypes.normalizedFilterValues(),
        excludedAttributeTypes = excludedAttributeTypes.normalizedFilterValues(),
    )

    /** Matches MIME prefixes and an editorial type using the same include-list precedence as serving. */
    fun matches(contentType: String?, attributeType: String?): Boolean =
        matchesFilter(contentType?.substringBefore(';'), includedContentTypePrefixes, excludedContentTypePrefixes, prefix = true) &&
            matchesFilter(attributeType, includedAttributeTypes, excludedAttributeTypes)

    companion object {
        /** Raw metadata asset families excluded from ordinary site/application recommendation surfaces. */
        val DEFAULT_EXCLUDED_CONTENT_TYPE_PREFIXES = listOf(
            "image/",
            "video/",
            "audio/",
            "font/",
            "model/",
            "application/octet-stream",
        )

        /** The metadata filter used by the default recommendation context. */
        val DEFAULT = RecommendationMetadataFilter()

        /** An unrestricted metadata filter. */
        val ALL = RecommendationMetadataFilter(excludedContentTypePrefixes = emptyList())
    }
}

internal fun List<String>.normalizedFilterValues(): List<String> =
    map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

internal fun matchesFilter(value: String?, included: List<String>, excluded: List<String>, prefix: Boolean = false): Boolean {
    val candidate = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val includedValues = included.normalizedFilterValues()
    fun matches(filter: String): Boolean = candidate != null && if (prefix) candidate.startsWith(filter) else candidate == filter
    return if (includedValues.isNotEmpty()) includedValues.any(::matches) else excluded.normalizedFilterValues().none(::matches)
}

package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Controls how metadata and collections are classified into a recommendation context.
 *
 * Metadata and collections have independent filters because their canonical type fields differ. A null
 * [collections] filter means the context has no collection candidates. The ordinary default excludes raw
 * metadata assets while retaining higher-level metadata and all collections.
 */
@Serializable
data class RecommendationContentFilter(
    val metadata: RecommendationMetadataFilter = RecommendationMetadataFilter.DEFAULT,
    val collections: RecommendationCollectionFilter? = RecommendationCollectionFilter.ALL,
) {

    companion object {
        /** The default filter for ordinary site and application recommendation surfaces. */
        val DEFAULT = RecommendationContentFilter()

        /** An unrestricted context for callers that intentionally want every candidate type. */
        val ALL = RecommendationContentFilter(metadata = RecommendationMetadataFilter.ALL)
    }
}

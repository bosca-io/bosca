package bosca.recommendations.model

import kotlinx.serialization.Serializable

/** API input for the metadata and collection filters attached to a recommendation context. */
@Serializable
data class RecommendationContentFilterInput(
    val metadata: RecommendationMetadataFilterInput = RecommendationMetadataFilterInput(),
    val collections: RecommendationCollectionFilterInput? = RecommendationCollectionFilterInput(),
) {
    /** Converts this API input to the persisted service model. */
    fun toModel() = RecommendationContentFilter(
        metadata = metadata.toModel(),
        collections = collections?.toModel(),
    )
}

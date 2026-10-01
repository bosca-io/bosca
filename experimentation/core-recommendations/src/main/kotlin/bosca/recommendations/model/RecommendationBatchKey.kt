package bosca.recommendations.model

import bosca.serialization.UUID

/** Stable GraphQL batch key for one recommendation result and its selected content representation. */
data class RecommendationBatchKey(
    val id: UUID,
    val metadataId: UUID?,
    val collectionId: UUID?,
    val collectionLanguageTag: String?,
    val strategyId: UUID,
    val inference: RecommendationInference? = null,
) {
    constructor(recommendation: Recommendation) : this(
        id = recommendation.id,
        metadataId = recommendation.metadataId,
        collectionId = recommendation.collectionId,
        collectionLanguageTag = recommendation.collectionLanguageTag,
        strategyId = recommendation.strategyId,
        inference = recommendation.inference,
    )
}

package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Identifies the candidate-generation or model-ranking signals that contributed to a recommendation.
 * A result can carry several sources when independent candidate sets are fused or a candidate is re-ranked
 * by the personalized model.
 */
@Serializable
enum class RecommendationSource {
    /** Similarity from the independently served item-to-item content model. */
    CONTENT_MODEL,

    /** Retrieval or ranking from the personalized TensorFlow model. */
    PERSONALIZED_MODEL,

    /** Globally trending content from an evaluated trending strategy. */
    TRENDING,

    /** Whole-audience item-to-item co-engagement. */
    CO_ENGAGEMENT,

    /** Item-to-item co-engagement conditioned on a viewer cohort. */
    COHORT_CO_ENGAGEMENT,
}

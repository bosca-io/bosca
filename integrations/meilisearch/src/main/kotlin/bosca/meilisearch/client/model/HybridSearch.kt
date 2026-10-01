package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Configuration for hybrid (keyword + semantic) search. The [semanticRatio]
 * controls the balance between keyword matching (0.0) and semantic
 * similarity (1.0).
 */
@Serializable
data class HybridSearch(
    val embedder: String? = null,
    val semanticRatio: Double? = null,
)
